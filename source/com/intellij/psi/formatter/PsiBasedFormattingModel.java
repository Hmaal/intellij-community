package com.intellij.psi.formatter;

import com.intellij.codeFormatting.general.FormatterUtil;
import com.intellij.lang.ASTNode;
import com.intellij.newCodeFormatting.FormattingModel;
import com.intellij.newCodeFormatting.FormattingDocumentModel;
import com.intellij.newCodeFormatting.Block;
import com.intellij.openapi.fileTypes.StdFileTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.impl.source.SourceTreeToPsiMap;
import com.intellij.psi.impl.source.codeStyle.Helper;
import com.intellij.psi.impl.source.jsp.JspxFileImpl;
import com.intellij.psi.impl.source.tree.ElementType;
import com.intellij.util.IncorrectOperationException;

import java.io.IOException;
import java.io.LineNumberReader;
import java.io.StringReader;

public class PsiBasedFormattingModel implements FormattingModel {
  
  private final ASTNode myASTNode;
  private final Project myProject;
  private final CodeStyleSettings mySettings;
  private final FormattingDocumentModelImpl myDocumentModel;
  private final Block myRootBlock;

  public PsiBasedFormattingModel(final PsiFile file,
                                 CodeStyleSettings settings, 
                                 final Block rootBlock) {
    mySettings = settings;
    myASTNode = SourceTreeToPsiMap.psiElementToTree(file);
    myProject = file.getProject();
    myDocumentModel = FormattingDocumentModelImpl.createOn(file);
    myRootBlock = rootBlock;
  }

  public int replaceWhiteSpace(TextRange textRange,
                               String whiteSpace,
                               final int blockLength) throws IncorrectOperationException {
    return replaceWithPSI(textRange, blockLength, whiteSpace);
  }

  private int replaceWithPSI(final TextRange textRange, int blockLength, final String whiteSpace)
    throws IncorrectOperationException {
    final int offset = textRange.getEndOffset();
    final ASTNode leafElement = findElementAt(offset);
    final int oldElementLength = leafElement.getTextRange().getLength();
    if (leafElement.getTextRange().getStartOffset() < textRange.getStartOffset()) {
      final int newElementLength = new Helper(StdFileTypes.JAVA, myProject).shiftIndentInside(leafElement, getSpaceCount(whiteSpace))
        .getTextRange().getLength();
      blockLength = blockLength - oldElementLength + newElementLength;
    }
    else {
      changeWhiteSpaceBeforeLeaf(whiteSpace, leafElement);
      if (leafElement.textContains('\n')
        && whiteSpace.indexOf('\n') >= 0) {
        try {
          Indent lastLineIndent = getLastLineIndent(leafElement.getText());
          Indent whiteSpaceIndent = createIndentOn(getLastLine(whiteSpace));
          final int shift = calcShift(lastLineIndent, whiteSpaceIndent);
          final int newElementLength = new Helper(StdFileTypes.JAVA, myProject).shiftIndentInside(leafElement, shift).getTextRange()
            .getLength();
          blockLength = blockLength - oldElementLength + newElementLength;
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    }

    return blockLength;
  }

  protected void changeWhiteSpaceBeforeLeaf(final String whiteSpace, final ASTNode leafElement) {
    FormatterUtil.replaceWhiteSpace(whiteSpace, leafElement, ElementType.WHITE_SPACE);
  }

  private int calcShift(final Indent lastLineIndent, final Indent whiteSpaceIndent) {
    final CodeStyleSettings.IndentOptions options = mySettings.JAVA_INDENT_OPTIONS;
    if (lastLineIndent.equals(whiteSpaceIndent)) return 0;
    if (options.USE_TAB_CHARACTER) {
      if (lastLineIndent.whiteSpaces > 0) {
        return whiteSpaceIndent.getSpacesCount(options);
      }
      else {
        return whiteSpaceIndent.tabs - lastLineIndent.tabs;
      }
    }
    else {
      if (lastLineIndent.tabs > 0) {
        return whiteSpaceIndent.getTabsCount(options);
      }
      else {
        return whiteSpaceIndent.whiteSpaces - lastLineIndent.whiteSpaces;
      }
    }
  }

  private int getSpaceCount(final String whiteSpace) throws IncorrectOperationException {
    try {
      final String lastLine = getLastLine(whiteSpace);
      if (lastLine != null) {
        return lastLine.length();
      }
      else {
        return 0;
      }
    }
    catch (IOException e) {
      throw new IncorrectOperationException(e.getLocalizedMessage());
    }
  }

  private ASTNode findElementAt(final int offset) {
    if (myASTNode.getElementType() == ElementType.JSPX_FILE) {
      final PsiFile[] psiRoots = ((JspxFileImpl)SourceTreeToPsiMap.treeElementToPsi(myASTNode)).getPsiRoots();
      for (int i = 0; i < psiRoots.length; i++) {
        PsiFile psiRoot = psiRoots[i];
        final PsiElement found = psiRoot.findElementAt(offset);
        if (found != null && found.getTextRange().getStartOffset() == offset) return SourceTreeToPsiMap.psiElementToTree(found);
      }
    }
    return myASTNode.findLeafElementAt(offset);
  }

  public void dispose() {
  }

  private class Indent {
    public int whiteSpaces = 0;
    public int tabs = 0;

    public boolean equals(final Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      final Indent indent = (Indent)o;

      if (tabs != indent.tabs) return false;
      if (whiteSpaces != indent.whiteSpaces) return false;

      return true;
    }

    public int hashCode() {
      int result;
      result = whiteSpaces;
      result = 29 * result + tabs;
      return result;
    }

    public int getTabsCount(final CodeStyleSettings.IndentOptions options) {
      final int tabsFromSpaces = whiteSpaces / options.TAB_SIZE;
      return tabs + tabsFromSpaces;
    }

    public int getSpacesCount(final CodeStyleSettings.IndentOptions options) {
      return whiteSpaces + tabs * options.TAB_SIZE;
    }
  }

  private Indent getLastLineIndent(final String text) throws IOException {
    String lastLine = getLastLine(text);
    if (lastLine == null) return new Indent();
    return createIndentOn(lastLine);
  }

  private Indent createIndentOn(final String lastLine) {
    final Indent result = new Indent();
    for (int i = 0; i < lastLine.length(); i++) {
      if (lastLine.charAt(i) == ' ') result.whiteSpaces += 1;
      if (lastLine.charAt(i) == '\t') result.tabs += 1;
    }
    return result;
  }

  private String getLastLine(final String text) throws IOException {
    final LineNumberReader lineNumberReader = new LineNumberReader(new StringReader(text));
    String line;
    String result = null;
    while ((line = lineNumberReader.readLine()) != null) {
      result = line;
    }
    return result;
  }

  public FormattingDocumentModel getDocumentModel() {
    return myDocumentModel;
  }

  public Block getRootBlock() {
    return myRootBlock;
  }
}
