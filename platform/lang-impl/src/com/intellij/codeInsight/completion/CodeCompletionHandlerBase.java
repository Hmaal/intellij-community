/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.codeInsight.completion;

import com.intellij.codeInsight.CodeInsightSettings;
import com.intellij.codeInsight.CodeInsightUtilBase;
import com.intellij.codeInsight.completion.impl.CompletionServiceImpl;
import com.intellij.codeInsight.editorActions.CompletionAutoPopupHandler;
import com.intellij.codeInsight.editorActions.smartEnter.SmartEnterProcessor;
import com.intellij.codeInsight.editorActions.smartEnter.SmartEnterProcessors;
import com.intellij.codeInsight.lookup.*;
import com.intellij.codeInsight.lookup.impl.LookupImpl;
import com.intellij.featureStatistics.FeatureUsageTracker;
import com.intellij.ide.DataManager;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.lang.Language;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.actionSystem.EditorActionManager;
import com.intellij.openapi.editor.ex.DocumentEx;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.Trinity;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.PsiFileEx;
import com.intellij.psi.impl.PsiModificationTrackerImpl;
import com.intellij.psi.impl.source.PostprocessReformattingAspect;
import com.intellij.psi.impl.source.PsiFileImpl;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil;
import com.intellij.psi.util.PsiUtilBase;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.reference.SoftReference;
import com.intellij.util.ThreeState;
import com.intellij.util.concurrency.Semaphore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class CodeCompletionHandlerBase {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInsight.completion.CodeCompletionHandlerBase");
  private final CompletionType myCompletionType;
  final boolean invokedExplicitly;
  final boolean synchronous;
  final boolean autopopup;

  public CodeCompletionHandlerBase(final CompletionType completionType) {
    this(completionType, true, false, true);
  }

  public CodeCompletionHandlerBase(CompletionType completionType, boolean invokedExplicitly, boolean autopopup, boolean synchronous) {
    myCompletionType = completionType;
    this.invokedExplicitly = invokedExplicitly;
    this.autopopup = autopopup;
    this.synchronous = synchronous;

    if (invokedExplicitly) {
      assert synchronous;
    }
    if (autopopup) {
      assert !invokedExplicitly;
    }
  }

  public final void invokeCompletion(final Project project, final Editor editor) {
    try {
      invokeCompletion(project, editor, 1);
    }
    catch (IndexNotReadyException e) {
      DumbService.getInstance(project).showDumbModeNotification("Code completion is not available here while indices are being built");
    }
  }

  public final void invokeCompletion(@NotNull final Project project, @NotNull final Editor editor, int time) {
    invokeCompletion(project, editor, time, false, false);
  }

  public final void invokeCompletion(@NotNull final Project project, @NotNull final Editor editor, int time, boolean hasModifiers, boolean restarted) {
    final PsiFile psiFile = PsiUtilBase.getPsiFileInEditor(editor, project);
    assert psiFile != null : "no PSI file: " + FileDocumentManager.getInstance().getFile(editor.getDocument());

    checkNoWriteAccess();

    CompletionAssertions.checkEditorValid(editor);

    if (editor.isViewer()) {
      editor.getDocument().fireReadOnlyModificationAttempt();
      return;
    }

    if (invokedExplicitly) {
      CompletionLookupArranger.applyLastCompletionStatisticsUpdate();
    }

    if (!CodeInsightUtilBase.prepareEditorForWrite(editor) ||
        !FileDocumentManager.getInstance().requestWriting(editor.getDocument(), project)) {
      return;
    }

    psiFile.putUserData(PsiFileEx.BATCH_REFERENCE_PROCESSING, Boolean.TRUE);

    CompletionPhase phase = CompletionServiceImpl.getCompletionPhase();
    boolean repeated = phase.indicator != null && phase.indicator.isRepeatedInvocation(myCompletionType, editor);
    /*
    if (repeated && isAutocompleteCommonPrefixOnInvocation() && phase.fillInCommonPrefix()) {
      return;
    }
    */

    int newTime = phase.newCompletionStarted(time, repeated);
    if (invokedExplicitly) {
      time = newTime;
    }
    if (CompletionServiceImpl.isPhase(CompletionPhase.InsertedSingleItem.class)) {
      CompletionServiceImpl.setCompletionPhase(CompletionPhase.NoCompletion);
    }
    CompletionServiceImpl.assertPhase(CompletionPhase.NoCompletion.getClass(), CompletionPhase.CommittingDocuments.class);

    if (time > 1) {
      if (myCompletionType == CompletionType.BASIC) {
        FeatureUsageTracker.getInstance().triggerFeatureUsed(CodeCompletionFeatures.SECOND_BASIC_COMPLETION);
      }
    }

    final CompletionInitializationContext[] initializationContext = {null};


    Runnable initCmd = new Runnable() {
      @Override
      public void run() {
        Runnable runnable = new Runnable() {
          @Override
          public void run() {
            EditorUtil.fillVirtualSpaceUntilCaret(editor);
            PsiDocumentManager.getInstance(project).commitAllDocuments();

            CompletionAssertions.assertCommitSuccessful(editor, psiFile);
            CompletionAssertions.checkEditorValid(editor);

            initializationContext[0] = runContributorsBeforeCompletion(editor, psiFile);
          }
        };
        ApplicationManager.getApplication().runWriteAction(runnable);
      }
    };
    if (autopopup) {
      CommandProcessor.getInstance().runUndoTransparentAction(initCmd);
      if (!restarted && shouldSkipAutoPopup(editor, psiFile)) {
        CompletionServiceImpl.setCompletionPhase(CompletionPhase.NoCompletion);
        return;
      }
    } else {
      CommandProcessor.getInstance().executeCommand(project, initCmd, null, null);
    }

    insertDummyIdentifier(initializationContext[0], hasModifiers, time);
  }

  private CompletionInitializationContext runContributorsBeforeCompletion(Editor editor, PsiFile psiFile) {
    final Ref<CompletionContributor> current = Ref.create(null);
    CompletionInitializationContext context = new CompletionInitializationContext(editor, psiFile, myCompletionType) {
      CompletionContributor dummyIdentifierChanger;

      @Override
      public void setDummyIdentifier(@NotNull String dummyIdentifier) {
        super.setDummyIdentifier(dummyIdentifier);

        if (dummyIdentifierChanger != null) {
          LOG.error("Changing the dummy identifier twice, already changed by " + dummyIdentifierChanger);
        }
        dummyIdentifierChanger = current.get();
      }
    };
    List<CompletionContributor> contributors = CompletionContributor.forLanguage(context.getPositionLanguage());
    Project project = psiFile.getProject();
    List<CompletionContributor> filteredContributors = DumbService.getInstance(project).filterByDumbAwareness(contributors);
    for (final CompletionContributor contributor : filteredContributors) {
      current.set(contributor);
      contributor.beforeCompletion(context);
      CompletionAssertions.checkEditorValid(editor);
      assert !PsiDocumentManager.getInstance(project).isUncommited(editor.getDocument()) : "Contributor " + contributor + " left the document uncommitted";
    }
    return context;
  }

  private static void checkNoWriteAccess() {
    if (!ApplicationManager.getApplication().isUnitTestMode()) {
      if (ApplicationManager.getApplication().isWriteAccessAllowed()) {
        throw new AssertionError("Completion should not be invoked inside write action");
      }
    }
  }

  private static boolean shouldSkipAutoPopup(Editor editor, PsiFile psiFile) {
    int offset = editor.getCaretModel().getOffset();
    int psiOffset = Math.max(0, offset - 1);

    PsiElement elementAt = InjectedLanguageUtil.findInjectedElementNoCommit(psiFile, psiOffset);
    if (elementAt == null) {
      elementAt = psiFile.findElementAt(psiOffset);
    }
    if (elementAt == null) return true;

    Language language = PsiUtilCore.findLanguageFromElement(elementAt);

    for (CompletionConfidence confidence : CompletionConfidenceEP.forLanguage(language)) {
      final ThreeState result = confidence.shouldSkipAutopopup(elementAt, psiFile, offset);
      if (result != ThreeState.UNSURE) {
        LOG.debug(confidence + " has returned shouldSkipAutopopup=" + result);
        return result == ThreeState.YES;
      }
    }
    return false;
  }

  @NotNull
  private LookupImpl obtainLookup(Editor editor) {
    CompletionAssertions.checkEditorValid(editor);
    LookupImpl existing = (LookupImpl)LookupManager.getActiveLookup(editor);
    if (existing != null && existing.isCompletion()) {
      existing.markReused();
      if (!autopopup) {
        existing.setFocusDegree(LookupImpl.FocusDegree.FOCUSED);
      }
      return existing;
    }

    LookupImpl lookup = (LookupImpl)LookupManager.getInstance(editor.getProject()).createLookup(editor, LookupElement.EMPTY_ARRAY, "",
                                                                                                new LookupArranger.DefaultArranger());
    if (editor.isOneLineMode()) {
      lookup.setCancelOnClickOutside(true);
      lookup.setCancelOnOtherWindowOpen(true);
    }
    lookup.setFocusDegree(autopopup ? LookupImpl.FocusDegree.UNFOCUSED : LookupImpl.FocusDegree.FOCUSED);
    return lookup;
  }

  private void doComplete(CompletionInitializationContext initContext,
                          boolean hasModifiers,
                          int invocationCount,
                          PsiFile hostCopy,
                          OffsetMap hostMap, OffsetTranslator translator) {
    final Editor editor = initContext.getEditor();
    CompletionAssertions.checkEditorValid(editor);

    CompletionContext context = createCompletionContext(hostCopy, hostMap.getOffset(CompletionInitializationContext.START_OFFSET), hostMap, initContext.getFile());
    CompletionParameters parameters = createCompletionParameters(invocationCount, context, editor);

    CompletionPhase phase = CompletionServiceImpl.getCompletionPhase();
    if (phase instanceof CompletionPhase.CommittingDocuments) {
      if (phase.indicator != null) {
        phase.indicator.closeAndFinish(false);
      }
      ((CompletionPhase.CommittingDocuments)phase).replaced = true;
    } else {
      CompletionServiceImpl.assertPhase(CompletionPhase.NoCompletion.getClass());
    }

    final Semaphore freezeSemaphore = new Semaphore();
    freezeSemaphore.down();
    final CompletionProgressIndicator indicator = new CompletionProgressIndicator(editor, parameters, this, freezeSemaphore,
                                                                                  initContext.getOffsetMap(), hasModifiers);
    Disposer.register(indicator, hostMap);
    Disposer.register(indicator, context.getOffsetMap());
    Disposer.register(indicator, translator);

    CompletionServiceImpl.setCompletionPhase(synchronous ? new CompletionPhase.Synchronous(indicator) : new CompletionPhase.BgCalculation(indicator));

    final AtomicReference<LookupElement[]> data = indicator.startCompletion(initContext);

    if (!synchronous) {
      return;
    }

    if (freezeSemaphore.waitFor(2000)) {
      final LookupElement[] allItems = data.get();
      if (allItems != null && !indicator.isRunning() && !indicator.isCanceled()) { // the completion is really finished, now we may auto-insert or show lookup
        completionFinished(initContext.getStartOffset(), initContext.getSelectionEndOffset(), indicator, allItems, hasModifiers);
        checkNotSync(indicator, allItems);
        return;
      }
    }

    CompletionServiceImpl.setCompletionPhase(new CompletionPhase.BgCalculation(indicator));
    indicator.showLookup();
  }

  private static void checkNotSync(CompletionProgressIndicator indicator, LookupElement[] allItems) {
    if (CompletionServiceImpl.isPhase(CompletionPhase.Synchronous.class)) {
      LOG.error("sync phase survived: " + Arrays.toString(allItems) + "; indicator=" + CompletionServiceImpl.getCompletionPhase().indicator + "; myIndicator=" + indicator);
      CompletionServiceImpl.setCompletionPhase(CompletionPhase.NoCompletion);
    }
  }

  private CompletionParameters createCompletionParameters(int invocationCount, final CompletionContext newContext, final Editor editor) {
    final int offset = newContext.getStartOffset();
    final PsiFile fileCopy = newContext.file;
    PsiFile originalFile = fileCopy.getOriginalFile();
    final PsiElement insertedElement = findCompletionPositionLeaf(newContext, offset, fileCopy, originalFile);
    insertedElement.putUserData(CompletionContext.COMPLETION_CONTEXT_KEY, newContext);
    return new CompletionParameters(insertedElement, originalFile, myCompletionType, offset, invocationCount, obtainLookup(editor));
  }

  @NotNull
  private static PsiElement findCompletionPositionLeaf(CompletionContext newContext, int offset, PsiFile fileCopy, PsiFile originalFile) {
    final PsiElement insertedElement = newContext.file.findElementAt(offset);
    CompletionAssertions.assertCompletionPositionPsiConsistent(newContext, offset, fileCopy, originalFile, insertedElement);
    assert insertedElement != null;
    return insertedElement;
  }

  private AutoCompletionDecision shouldAutoComplete(final CompletionProgressIndicator indicator, final LookupElement[] items) {
    if (!invokedExplicitly) {
      return AutoCompletionDecision.SHOW_LOOKUP;
    }
    final CompletionParameters parameters = indicator.getParameters();
    final LookupElement item = items[0];
    if (items.length == 1) {
      final AutoCompletionPolicy policy = getAutocompletionPolicy(item);
      if (policy == AutoCompletionPolicy.NEVER_AUTOCOMPLETE) return AutoCompletionDecision.SHOW_LOOKUP;
      if (policy == AutoCompletionPolicy.ALWAYS_AUTOCOMPLETE) return AutoCompletionDecision.insertItem(item);
      if (!indicator.getLookup().itemMatcher(item).isStartMatch(item)) return AutoCompletionDecision.SHOW_LOOKUP;
    }
    if (!isAutocompleteOnInvocation(parameters.getCompletionType())) {
      return AutoCompletionDecision.SHOW_LOOKUP;
    }
    if (isInsideIdentifier(indicator.getOffsetMap())) {
      return AutoCompletionDecision.SHOW_LOOKUP;
    }
    if (items.length == 1 && getAutocompletionPolicy(item) == AutoCompletionPolicy.GIVE_CHANCE_TO_OVERWRITE) {
      return AutoCompletionDecision.insertItem(item);
    }

    AutoCompletionContext context = new AutoCompletionContext(parameters, items, indicator.getOffsetMap(), indicator.getLookup());
    for (final CompletionContributor contributor : CompletionContributor.forParameters(parameters)) {
      final AutoCompletionDecision decision = contributor.handleAutoCompletionPossibility(context);
      if (decision != null) {
        return decision;
      }
    }

    return AutoCompletionDecision.SHOW_LOOKUP;
  }

  @Nullable
  private static AutoCompletionPolicy getAutocompletionPolicy(LookupElement element) {
    final AutoCompletionPolicy policy = AutoCompletionPolicy.getPolicy(element);
    if (policy != null) {
      return policy;
    }

    final LookupItem item = element.as(LookupItem.CLASS_CONDITION_KEY);
    if (item != null) {
      return item.getAutoCompletionPolicy();
    }

    return null;
  }

  private static boolean isInsideIdentifier(final OffsetMap offsetMap) {
    return offsetMap.getOffset(CompletionInitializationContext.IDENTIFIER_END_OFFSET) != offsetMap.getOffset(CompletionInitializationContext.SELECTION_END_OFFSET);
  }


  protected void completionFinished(final int offset1,
                                    final int offset2,
                                    final CompletionProgressIndicator indicator,
                                    final LookupElement[] items, boolean hasModifiers) {
    if (items.length == 0) {
      LookupManager.getInstance(indicator.getProject()).hideActiveLookup();
      indicator.handleEmptyLookup(true);
      checkNotSync(indicator, items);
      return;
    }

    LOG.assertTrue(!indicator.isRunning(), "running");
    LOG.assertTrue(!indicator.isCanceled(), "canceled");

    indicator.getLookup().refreshUi(true, false);
    final AutoCompletionDecision decision = shouldAutoComplete(indicator, items);
    if (decision == AutoCompletionDecision.SHOW_LOOKUP) {
      CompletionServiceImpl.setCompletionPhase(new CompletionPhase.ItemsCalculated(indicator));
      indicator.getLookup().setCalculating(false);
      indicator.showLookup();
    }
    else if (decision instanceof AutoCompletionDecision.InsertItem) {
      final Runnable restorePrefix = rememberDocumentState(indicator.getEditor());

      final LookupElement item = ((AutoCompletionDecision.InsertItem)decision).getElement();
      CommandProcessor.getInstance().executeCommand(indicator.getProject(), new Runnable() {
                                                      @Override
                                                      public void run() {
                                                        indicator.setMergeCommand();
                                                        indicator.getLookup().finishLookup(Lookup.AUTO_INSERT_SELECT_CHAR, item);
                                                      }
                                                    }, "Autocompletion", null);



      // the insert handler may have started a live template with completion
      if (CompletionService.getCompletionService().getCurrentCompletion() == null &&
          !ApplicationManager.getApplication().isUnitTestMode()) {
        CompletionServiceImpl.setCompletionPhase(hasModifiers? new CompletionPhase.InsertedSingleItem(indicator, restorePrefix) : CompletionPhase.NoCompletion);
      }
      checkNotSync(indicator, items);
    } else if (decision == AutoCompletionDecision.CLOSE_LOOKUP) {
      LookupManager.getInstance(indicator.getProject()).hideActiveLookup();
      checkNotSync(indicator, items);
    }
  }

  private void insertDummyIdentifier(final CompletionInitializationContext initContext,
                                     final boolean hasModifiers,
                                     final int invocationCount) {
    final PsiFile originalFile = initContext.getFile();
    InjectedLanguageManager manager = InjectedLanguageManager.getInstance(originalFile.getProject());
    final PsiFile hostFile = manager.getTopLevelFile(originalFile);
    final Editor hostEditor = InjectedLanguageUtil.getTopLevelEditor(initContext.getEditor());
    final OffsetMap hostMap = translateOffsetMapToHost(initContext, originalFile, hostFile, hostEditor);

    final PsiFile[] hostCopy = {null};
    CommandProcessor.getInstance().runUndoTransparentAction(new Runnable() {
      @Override
      public void run() {
        AccessToken token = WriteAction.start();
        try {
          hostCopy[0] = createFileCopy(hostFile, initContext.getStartOffset(), initContext.getSelectionEndOffset());
        }
        finally {
          token.finish();
        }
      }
    });

    final Document copyDocument = hostCopy[0].getViewProvider().getDocument();
    assert copyDocument != null : "no document";
    final OffsetTranslator translator = new OffsetTranslator(hostEditor.getDocument(), initContext.getFile(), copyDocument);

    CompletionAssertions.checkEditorValid(initContext.getEditor());
    CommandProcessor.getInstance().runUndoTransparentAction(new Runnable() {
      @Override
      public void run() {
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
          @Override
          public void run() {
            String dummyIdentifier = initContext.getDummyIdentifier();
            if (StringUtil.isEmpty(dummyIdentifier)) return;

            int startOffset = hostMap.getOffset(CompletionInitializationContext.START_OFFSET);
            int endOffset = hostMap.getOffset(CompletionInitializationContext.SELECTION_END_OFFSET);
            copyDocument.replaceString(startOffset, endOffset, dummyIdentifier);
          }
        });
      }
    });
    CompletionAssertions.checkEditorValid(initContext.getEditor());

    final Project project = originalFile.getProject();

    if (!synchronous) {
      if (CompletionServiceImpl.isPhase(CompletionPhase.NoCompletion.getClass()) ||
          !CompletionServiceImpl.assertPhase(CompletionPhase.CommittingDocuments.class)) {
        Disposer.dispose(translator);
        return;
      }

      final CompletionPhase.CommittingDocuments phase = (CompletionPhase.CommittingDocuments)CompletionServiceImpl.getCompletionPhase();

      CompletionAutoPopupHandler.runLaterWithCommitted(project, copyDocument, new Runnable() {
        @Override
        public void run() {
          if (phase.checkExpired()) {
            Disposer.dispose(translator);
            return;
          }
          doComplete(initContext, hasModifiers, invocationCount, hostCopy[0], hostMap, translator);
        }
      });
    }
    else {
      PsiDocumentManager.getInstance(project).commitDocument(copyDocument);

      doComplete(initContext, hasModifiers, invocationCount, hostCopy[0], hostMap, translator);
    }
  }

  private static OffsetMap translateOffsetMapToHost(CompletionInitializationContext initContext,
                                                    PsiFile context,
                                                    PsiFile hostFile,
                                                    Editor hostEditor) {
    final InjectedLanguageManager injectedLanguageManager = InjectedLanguageManager.getInstance(hostFile.getProject());
    final OffsetMap hostMap = new OffsetMap(hostEditor.getDocument());
    final OffsetMap original = initContext.getOffsetMap();
    for (final OffsetKey key : original.getAllOffsets()) {
      hostMap.addOffset(key, injectedLanguageManager.injectedToHost(context, original.getOffset(key)));
    }
    return hostMap;
  }

  private static CompletionContext createCompletionContext(PsiFile hostCopy,
                                                           int hostStartOffset,
                                                           OffsetMap hostMap, PsiFile originalFile) {
    CompletionAssertions.assertHostInfo(hostCopy, hostMap);

    InjectedLanguageManager injectedLanguageManager = InjectedLanguageManager.getInstance(hostCopy.getProject());
    CompletionContext context;
    PsiFile injected = InjectedLanguageUtil.findInjectedPsiNoCommit(hostCopy, hostStartOffset);
    if (injected != null) {
      if (injected instanceof PsiFileImpl && injectedLanguageManager.isInjectedFragment(originalFile)) {
        ((PsiFileImpl)injected).setOriginalFile(originalFile);
      }
      DocumentWindow documentWindow = InjectedLanguageUtil.getDocumentWindow(injected);
      CompletionAssertions.assertInjectedOffsets(hostStartOffset, injectedLanguageManager, injected, documentWindow);

      context = new CompletionContext(injected, translateOffsetMapToInjected(hostMap, documentWindow));
    } else {
      context = new CompletionContext(hostCopy, hostMap);
    }

    CompletionAssertions.assertFinalOffsets(originalFile, context, injected);

    return context;
  }

  private static OffsetMap translateOffsetMapToInjected(OffsetMap hostMap, DocumentWindow injectedDocument) {
    final OffsetMap map = new OffsetMap(injectedDocument);
    for (final OffsetKey key : hostMap.getAllOffsets()) {
      map.addOffset(key, injectedDocument.hostToInjected(hostMap.getOffset(key)));
    }
    return map;
  }

  protected void lookupItemSelected(final CompletionProgressIndicator indicator, @NotNull final LookupElement item, final char completionChar,
                                         final List<LookupElement> items) {
    if (indicator.isAutopopupCompletion()) {
      FeatureUsageTracker.getInstance().triggerFeatureUsed(CodeCompletionFeatures.EDITING_COMPLETION_BASIC);
    }

    CompletionAssertions.WatchingInsertionContext context = null;
    try {
      Lookup lookup = indicator.getParameters().getLookup();
      CompletionLookupArranger.StatisticsUpdate update = CompletionLookupArranger.collectStatisticChanges(item, lookup);
      context = insertItemHonorBlockSelection(indicator, item, completionChar, items, update);
      CompletionLookupArranger.trackStatistics(context, update);
    }
    finally {
      afterItemInsertion(indicator, context == null ? null : context.getLaterRunnable());
    }

  }

  private static CompletionAssertions.WatchingInsertionContext insertItemHonorBlockSelection(CompletionProgressIndicator indicator,
                                                                        LookupElement item,
                                                                        char completionChar,
                                                                        List<LookupElement> items,
                                                                        CompletionLookupArranger.StatisticsUpdate update) {
    final Editor editor = indicator.getEditor();

    final int caretOffset = editor.getCaretModel().getOffset();
    int idEndOffset = indicator.getIdentifierEndOffset();
    if (idEndOffset < 0) {
      idEndOffset = CompletionInitializationContext.calcDefaultIdentifierEnd(editor, caretOffset);
    }

    CompletionAssertions.WatchingInsertionContext context = null;
    if (editor.getSelectionModel().hasBlockSelection() && editor.getSelectionModel().getBlockSelectionEnds().length > 0) {
      List<RangeMarker> insertionPoints = new ArrayList<RangeMarker>();
      int idDelta = 0;
      Document document = editor.getDocument();
      int caretLine = document.getLineNumber(editor.getCaretModel().getOffset());

      for (int point : editor.getSelectionModel().getBlockSelectionEnds()) {
        insertionPoints.add(document.createRangeMarker(point, point));
        if (document.getLineNumber(point) == document.getLineNumber(idEndOffset)) {
          idDelta = idEndOffset - point;
        }
      }

      List<RangeMarker> caretsAfter = new ArrayList<RangeMarker>();
      for (RangeMarker marker : insertionPoints) {
        if (marker.isValid()) {
          int insertionPoint = marker.getStartOffset();
          context = insertItem(indicator, item, completionChar, items, update, editor, insertionPoint, idDelta + insertionPoint);
          int offset = editor.getCaretModel().getOffset();
          caretsAfter.add(document.createRangeMarker(offset, offset));
        }
      }
      assert context != null;

      restoreBlockSelection(editor, caretsAfter, caretLine);

      for (RangeMarker insertionPoint : insertionPoints) {
        insertionPoint.dispose();
      }
      for (RangeMarker marker : caretsAfter) {
        marker.dispose();
      }

    } else {
      context = insertItem(indicator, item, completionChar, items, update, editor, caretOffset, idEndOffset);
    }
    return context;
  }

  private static void afterItemInsertion(final CompletionProgressIndicator indicator, final Runnable laterRunnable) {
    if (laterRunnable != null) {
      final Runnable runnable1 = new Runnable() {
        @Override
        public void run() {
          if (!indicator.getProject().isDisposed()) {
            laterRunnable.run();
          }
          indicator.disposeIndicator();
        }
      };
      if (ApplicationManager.getApplication().isUnitTestMode()) {
        runnable1.run();
      }
      else {
        ApplicationManager.getApplication().invokeLater(runnable1);
      }
    }
    else {
      indicator.disposeIndicator();
    }
  }

  private static void restoreBlockSelection(Editor editor, List<RangeMarker> caretsAfter, int caretLine) {
    int column = -1;
    int minLine = Integer.MAX_VALUE;
    int maxLine = -1;
    for (RangeMarker marker : caretsAfter) {
      if (marker.isValid()) {
        LogicalPosition lp = editor.offsetToLogicalPosition(marker.getStartOffset());
        if (column == -1) {
          column = lp.column;
        } else if (column != lp.column) {
          return;
        }
        minLine = Math.min(minLine, lp.line);
        maxLine = Math.max(maxLine, lp.line);

        if (lp.line == caretLine) {
          editor.getCaretModel().moveToLogicalPosition(lp);
        }
      }
    }
    editor.getSelectionModel().setBlockSelection(new LogicalPosition(minLine, column), new LogicalPosition(maxLine, column));
  }

  private static CompletionAssertions.WatchingInsertionContext insertItem(final CompletionProgressIndicator indicator,
                                                     final LookupElement item,
                                                     final char completionChar,
                                                     List<LookupElement> items,
                                                     final CompletionLookupArranger.StatisticsUpdate update,
                                                     final Editor editor, final int caretOffset, final int idEndOffset) {
    editor.getCaretModel().moveToOffset(caretOffset);
    final int initialStartOffset = caretOffset - item.getLookupString().length();
    assert initialStartOffset >= 0 : "negative startOffset: " + caretOffset + "; " + item.getLookupString();

    indicator.getOffsetMap().addOffset(CompletionInitializationContext.START_OFFSET, initialStartOffset);
    indicator.getOffsetMap().addOffset(CompletionInitializationContext.SELECTION_END_OFFSET, caretOffset);
    indicator.getOffsetMap().addOffset(CompletionInitializationContext.IDENTIFIER_END_OFFSET, idEndOffset);

    final CompletionAssertions.WatchingInsertionContext
      context = new CompletionAssertions.WatchingInsertionContext(indicator, completionChar, items, editor);
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      @Override
      public void run() {
        if (caretOffset != idEndOffset && completionChar == Lookup.REPLACE_SELECT_CHAR) {
          editor.getDocument().deleteString(caretOffset, idEndOffset);
        }

        assert context.getStartOffset() >= 0 : "stale startOffset: was " + initialStartOffset + "; selEnd=" + caretOffset + "; idEnd=" + idEndOffset + "; file=" + context.getFile();
        assert context.getTailOffset() >= 0 : "stale tail: was " + initialStartOffset + "; selEnd=" + caretOffset + "; idEnd=" + idEndOffset + "; file=" + context.getFile();

        item.handleInsert(context);
        Project project = indicator.getProject();
        PostprocessReformattingAspect.getInstance(project).doPostponedFormatting();

        if (context.shouldAddCompletionChar()) {
          addCompletionChar(project, context, item, editor, indicator, completionChar);
        }
        context.stopWatching();
        editor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
      }
    });
    update.addSparedChars(indicator, item, context, completionChar);
    return context;
  }

  private static void addCompletionChar(Project project,
                                        CompletionAssertions.WatchingInsertionContext context,
                                        LookupElement item,
                                        Editor editor, CompletionProgressIndicator indicator, char completionChar) {
    int tailOffset = context.getTailOffset();
    if (tailOffset < 0) {
      LOG.info("tailOffset<0 after inserting " + item + " of " + item.getClass() + "; invalidated at: " + context.invalidateTrace + "\n--------");
    }
    else {
      editor.getCaretModel().moveToOffset(tailOffset);
    }
    if (context.getCompletionChar() == Lookup.COMPLETE_STATEMENT_SELECT_CHAR) {
      final Language language = PsiUtilBase.getLanguageInEditor(editor, project);
      final List<SmartEnterProcessor> processors = SmartEnterProcessors.INSTANCE.forKey(language);
      if (processors.size() > 0) {
        for (SmartEnterProcessor processor : processors) {
          processor.process(project, editor, indicator.getParameters().getOriginalFile());
        }
      }
    }
    else {
      DataContext dataContext = DataManager.getInstance().getDataContext(editor.getContentComponent());
      EditorActionManager.getInstance().getTypedAction().getHandler().execute(editor, completionChar, dataContext);
    }
  }

  private static final Key<SoftReference<Trinity<PsiFile, Document, Long>>> FILE_COPY_KEY = Key.create("CompletionFileCopy");

  private static boolean isCopyUpToDate(Document document, @NotNull PsiFile file) {
    if (!file.isValid()) {
      return false;
    }
    // the psi file cache might have been cleared by some external activity,
    // in which case PSI-document sync may stop working
    PsiFile current = PsiDocumentManager.getInstance(file.getProject()).getPsiFile(document);
    return current != null && current.getViewProvider().getPsi(file.getLanguage()) == file;
  }

  private static PsiFile createFileCopy(PsiFile file, long caret, long selEnd) {
    final VirtualFile virtualFile = file.getVirtualFile();
    boolean mayCacheCopy = file.isPhysical() &&
                           // we don't want to cache code fragment copies even if they appear to be physical
                           virtualFile != null && virtualFile.isInLocalFileSystem();
    long combinedOffsets = caret + (selEnd << 32);
    if (mayCacheCopy) {
      final Trinity<PsiFile, Document, Long> cached = SoftReference.dereference(file.getUserData(FILE_COPY_KEY));
      if (cached != null && cached.first.getClass().equals(file.getClass()) && isCopyUpToDate(cached.second, cached.first)) {
        final PsiFile copy = cached.first;
        if (copy.getViewProvider().getModificationStamp() > file.getViewProvider().getModificationStamp() && 
            cached.third.longValue() != combinedOffsets) {
          // the copy PSI might have some caches that are not cleared on its modification because there are no events in the copy
          //   so, clear all the caches
          // hopefully it's a rare situation that the user invokes completion in different parts of the file 
          //   without modifying anything physical in between
          ((PsiModificationTrackerImpl) file.getManager().getModificationTracker()).incCounter();
        }
        final Document document = cached.second;
        assert document != null;
        file.putUserData(FILE_COPY_KEY, new SoftReference<Trinity<PsiFile,Document, Long>>(Trinity.create(copy, document, combinedOffsets)));
        document.setText(file.getText());
        return copy;
      }
    }

    final PsiFile copy = (PsiFile)file.copy();
    if (mayCacheCopy) {
      final Document document = copy.getViewProvider().getDocument();
      assert document != null;
      file.putUserData(FILE_COPY_KEY, new SoftReference<Trinity<PsiFile,Document, Long>>(Trinity.create(copy, document, combinedOffsets)));
    }
    return copy;
  }

  private static boolean isAutocompleteOnInvocation(final CompletionType type) {
    final CodeInsightSettings settings = CodeInsightSettings.getInstance();
    if (type == CompletionType.SMART) {
      return settings.AUTOCOMPLETE_ON_SMART_TYPE_COMPLETION;
    }
    return settings.AUTOCOMPLETE_ON_CODE_COMPLETION;
  }

  private static Runnable rememberDocumentState(final Editor _editor) {
    final Editor editor = InjectedLanguageUtil.getTopLevelEditor(_editor);
    final String documentText = editor.getDocument().getText();
    final int caret = editor.getCaretModel().getOffset();
    final int selStart = editor.getSelectionModel().getSelectionStart();
    final int selEnd = editor.getSelectionModel().getSelectionEnd();

    final int vOffset = editor.getScrollingModel().getVerticalScrollOffset();
    final int hOffset = editor.getScrollingModel().getHorizontalScrollOffset();

    return new Runnable() {
      @Override
      public void run() {
        DocumentEx document = (DocumentEx) editor.getDocument();

        document.replaceString(0, document.getTextLength(), documentText);
        editor.getCaretModel().moveToOffset(caret);
        editor.getSelectionModel().setSelection(selStart, selEnd);

        editor.getScrollingModel().scrollHorizontally(hOffset);
        editor.getScrollingModel().scrollVertically(vOffset);
      }
    };
  }
}
