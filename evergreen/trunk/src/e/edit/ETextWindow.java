package e.edit;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.undo.*;
import e.gui.*;
import e.util.*;

/**
A text-editing component.
*/
public class ETextWindow extends ETextComponent implements DocumentListener {
    /**
     * Ensures that the TagsPanel is empty if the focused window isn't an ETextWindow.
     */
    static {
        new KeyboardFocusMonitor() {
            public void focusChanged(Component oldOwner, Component newOwner) {
                if (newOwner instanceof EWindow && newOwner instanceof ETextWindow == false) {
                    Edit.getTagsPanel().ensureTagsAreHidden();
                }
            }
        };
    }
    
    private static ETextWindow mostRecentlyFocusedTextWindow;
    
    protected String filename;
    protected File file;
    private long lastModifiedTime;
    protected ETextArea text;
    private boolean isDirty;
    private BirdView birdView;
    private TagsUpdater tagsUpdater;
    
    /**
     * Used to display a watermark to indicate such things as a read-only file.
     */
    private WatermarkViewPort watermarkViewPort;
    
    private static final Color FOCUSED_SELECTION_COLOR = new Color(0.70f, 0.83f, 1.00f);
    private static final Color UNFOCUSED_SELECTION_COLOR = new Color(0.83f, 0.83f, 0.83f);
    
    public static final String UNKNOWN = "Unknown";
    public static final String C_PLUS_PLUS = "C++";
    public static final String JAVA = "Java";
    public static final String RUBY = "Ruby";
    public static final String PERL = "Perl";
    
    private String fileType = UNKNOWN;
    
    private static final Hashtable KEYWORDS_MAP = new Hashtable();

    private Timer findResultsUpdater;
    
    static {
        initKeywordsFor(C_PLUS_PLUS);
        initKeywordsFor(JAVA);
        initKeywordsFor(RUBY);
    }
    
    private static void initKeywordsFor(String language) {
        HashSet keywords = new HashSet();
        String keywordsFileName = Edit.getResourceFilename("keywords-" + language);
        String[] keywordArray = StringUtilities.readLinesFromFile(keywordsFileName);
        for (int i = 0; i < keywordArray.length; i++) {
            if (keywordArray[i].startsWith("#")) {
                continue; // Ignore comments.
            }
            keywords.add(keywordArray[i]);
        }
        KEYWORDS_MAP.put(language, keywords);
    }
    
    public ETextWindow(String filename) {
        super(filename);
        this.filename = filename;
        this.file = FileUtilities.fileFromString(filename);
        this.text = new ETextArea();
        attachPopupMenuTo(text);
        
        this.watermarkViewPort = new WatermarkViewPort();
        watermarkViewPort.setView(text);
        JScrollPane scrollPane = new JScrollPane();
        scrollPane.setViewport(watermarkViewPort);
        
        initFocusListener();
        tagsUpdater = new TagsUpdater(this);
        this.birdView = new BirdView(this, scrollPane.getVerticalScrollBar());
        add(scrollPane, BorderLayout.CENTER);
        add(birdView, BorderLayout.EAST);
        fillWithContent();
        initFindResultsUpdater();
    }
    
    private void initFindResultsUpdater() {
        findResultsUpdater = new Timer(150, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                updateFindResults();
            }
        });
        findResultsUpdater.setRepeats(false);
    }

    public static void rememberFocus() {
        if (mostRecentlyFocusedTextWindow != null) {
            Workspace workspace = (Workspace) SwingUtilities.getAncestorOfClass(Workspace.class, mostRecentlyFocusedTextWindow);
            workspace.rememberFocusedTextWindow(mostRecentlyFocusedTextWindow);
        }
    }
    
    private void initFocusListener() {
        text.addFocusListener(new FocusListener() {
            public void focusGained(FocusEvent e) {
                mostRecentlyFocusedTextWindow = ETextWindow.this;
                text.setSelectionColor(FOCUSED_SELECTION_COLOR);
                updateWatermark();
            }
            
            public void focusLost(FocusEvent e) {
                text.setSelectionColor(UNFOCUSED_SELECTION_COLOR);
            }
        });
    }
    
    public BirdView getBirdView() {
        return birdView;
    }
    
    public String getFilename() {
        return filename;
    }
    
    /** Returns the grep-style ":<line>:<column>" address for the caret position. */
    public String getAddress() {
        try {
            int caretPosition = text.getCaretPosition();
            int lineNumber = 1 + text.getLineOfOffset(caretPosition);
            int lineStart = text.getLineStartOffset(lineNumber - 1);
            int columnNumber = 1 + emacsDistance(caretPosition, lineStart);
            return ":" + lineNumber + ":" + columnNumber;
        } catch (BadLocationException ex) {
            return "";
        }
    }
    
    public void requestFocus() {
        text.requestFocus();
    }
    
    //
    // DocumentListener interface.
    //

    public void changedUpdate(DocumentEvent e) {
        /*
         * This represents a style change, which should not be
         * considered as a dirtying because styles are meaningless
         * in a programmer's editor, and don't get saved.
         */
    }
    
    public void insertUpdate(DocumentEvent e) {
        markAsDirty();
    }
    
    public void removeUpdate(DocumentEvent e) {
        markAsDirty();
    }
    
    public UndoManager getUndoManager() {
        return text.getUndoManager();
    }
    
    /** Tests whether the 'content' looks like a Unix shell script. */
    private static boolean isInterpretedContent(String content, String interpreter) {
        return Pattern.compile("^#![^\\n]*" + interpreter).matcher(content).find();
    }
    
    /** Tests whether the 'content' looks like a Unix shell script, of the Ruby variety. */
    private static boolean isRubyContent(String content) {
        return isInterpretedContent(content, "ruby");
    }
    
    /** Tests whether the 'content' looks like a Unix shell script, of the Perl variety. */
    private static boolean isPerlContent(String content) {
        return isInterpretedContent(content, "perl");
    }
    
    /**
     * Tests whether the 'content' looks like a C++ file.
     *
     * A standard C++ header file (such as <string>) might not have any extension,
     * though it's likely in that case start with #ifndef.
     *
     * GNU headers tend to have an emacs mode hint, so let's obey those too (I think
     * emacs scans the whole file, but GNU headers seem to use the first line).
     */
    private static boolean isCPlusPlusContent(String content) {
        return content.startsWith("#ifndef") || content.matches(".*" + StringUtilities.regularExpressionFromLiteral("-*- C++ -*-") + ".*");
    }
    
    /** Attaches a set of keywords to a Document, tipping the spelling checker off that some words are okay. */
    private void initKeywordsForDocument() {
        HashSet keywords = (HashSet) KEYWORDS_MAP.get(fileType);
        if (keywords == null) {
            keywords = new HashSet();
        }
        
        if (isJava()) {
            JavaResearcher.addJavaWords(keywords);
        }
        
        text.getDocument().putProperty(JTextComponentSpellingChecker.KEYWORDS_DOCUMENT_PROPERTY, keywords);
    }
    
    public void updateWatermark() {
        watermarkViewPort.setSerious(false);
        ArrayList items = new ArrayList();
        if (file.exists() == false) {
            items.add("(deleted)");
        }
        if (file.exists() && file.canWrite() == false) {
            items.add("(read-only)");
        }
        if (isOutOfDateWithRespectToDisk()) {
            items.add("(out-of-date)");
            watermarkViewPort.setSerious(true);
        }
        watermarkViewPort.setWatermark(items.size() > 0 ? StringUtilities.join(items, " ") : null);
    }
    
    /**
     * Invoked during construction. Override this if you don't want to read from a file with
     * the same name as the window's title.
     */
    public void fillWithContent() {
        try {
            String content  = StringUtilities.readFile(file.getAbsolutePath());
            lastModifiedTime = file.lastModified();
            
            if (filename.endsWith(".java")) {
                fileType = JAVA;
                text.setIndenter(new JavaIndenter());
            } else if (filename.endsWith(".rb") || isRubyContent(content)) {
                fileType = RUBY;
                text.setIndenter(new RubyIndenter());
            } else if (filename.endsWith(".cpp") || filename.endsWith(".hpp") || filename.endsWith(".c") || filename.endsWith(".h") || filename.endsWith(".m") || filename.endsWith(".mm") || content.startsWith("#ifndef") || isCPlusPlusContent(content)) {
                fileType = C_PLUS_PLUS;
                text.setIndenter(new JavaIndenter());
            } else if (filename.endsWith(".pl") || isPerlContent(content)) {
                fileType = PERL;
                text.setIndenter(new JavaIndenter());
            }
            initKeywordsForDocument();
            updateWatermark();
            text.setText(content);
            text.setAppropriateFont();
            text.getIndenter().setIndentationPropertyBasedOnContent(text, content);
            text.getUndoManager().discardAllEdits();
            text.getDocument().addDocumentListener(this);
            if (fileType != UNKNOWN) {
                text.enableAutoIndent();
                text.getDocument().addDocumentListener(new UnmatchedBracketHighlighter(text));
            }
            markAsClean();
            getTitleBar().checkForCounterpart(); // If we don't do this, we don't get the icon until we get focus.
        } catch (Throwable th) {
            Log.warn("in ContentLoader exception handler", th);
            Edit.showAlert("Open", "Couldn't open file '" + th.getMessage() + "'.");
            throw new RuntimeException("don't open this window");
        }
    }
    
    public void revertToSaved() {
        boolean revert = Edit.askQuestion("Revert", "Revert to saved version of '" + file.getName() + "'?\nReverting will lose your current changes.", "Revert");
        if (revert) {
            int originalCaretPosition = text.getCaretPosition();
            fillWithContent();
            text.setCaretPosition(originalCaretPosition);
            Edit.showStatus("Reverted to saved version of " + filename);
        }
    }
    
    public ETextArea getText() {
        return text;
    }
    
    public void windowClosing() {
        if (findResultsUpdater != null) {
            findResultsUpdater.stop();
            findResultsUpdater = null;
        }
        Edit.showStatus("Closed " + filename);
        this.file = null;
        getWorkspace().unregisterTextComponent(getText());
        // FIXME: what else needs doing to ensure that we give back memory?
    }
    
    /**
     * Closes this text window if the text isn't dirty.
     */
    public void closeWindow() {
        if (isDirty()) {
            boolean discard = Edit.askQuestion("Close", "Do you want to discard the changes you made to the file\n'" + filename + "'?", "Discard");
            if (discard == false) {
                return;
            }
        }
        Edit.getTagsPanel().ensureTagsAreHidden();
        super.closeWindow();
    }
    
    public Collection getPopupMenuItems() {
        ArrayList items = new ArrayList();
        items.add(new OpenSelectionAction());
        items.add(new FindFilesContainingSelectionAction());
        items.add(new RevertToSavedAction());
        addContextSpecificMenuItems(items);
        addExternalToolMenuItems(items);
        return items;
    }

    public void addExternalToolMenuItems(final Collection items) {
        ExternalToolsParser toolsParser = new ExternalToolsParser() {
            private boolean needSeparator = true;
            
            public void addItem(Action action) {
                addAction(action);
            }

            public void addItem(Action action, char keyboardEquivalent) {
                addAction(action);
            }

            public void addAction(Action action) {
                if (needSeparator) {
                    addSeparator();
                    needSeparator = false;
                }
                /* Ignore ExternalToolActions that aren't context-sensitive. */
                if (action instanceof ExternalToolAction) {
                    if (((ExternalToolAction) action).isContextSensitive() == false) {
                        return;
                    }
                }
                items.add(action);
            }

            public void addSeparator() {
                items.add(null);
            }
        };
        toolsParser.parse();
    }

    /**
     * Returns the filename of the counterpart to this file, or null.
     * A Java .java file, for example, has no counterpart. A C++ .cpp
     * file, on the other hand, may have a .h counterpart (and vice
     * versa).
     */
    public String getCounterpartFilename() {
        // Work out what the counterpart would be called.
        String basename = file.getName().replaceAll("\\..*$", "");
        String counterpartFilename = null;
        
        if (filename.endsWith(".cpp")) {
            counterpartFilename = basename + ".h";
        } else if (filename.endsWith(".h")) {
            counterpartFilename = basename + ".cpp";
        } else {
            return null;
        }

        // See if the counterpart exists.
        File counterpartFile = FileUtilities.fileFromParentAndString(file.getParent(), counterpartFilename);
        return (counterpartFile.exists() ? counterpartFilename : null);
    }

    public void switchToCounterpart() {
        String counterpartFilename = getCounterpartFilename();
        if (counterpartFilename != null) {
            Edit.openFile(getContext() + File.separator + counterpartFilename);
        } else {
            Edit.showAlert("Switch To Counterpart", "File '" + filename + "' has no counterpart.");
        }
    }

    public boolean isCPlusPlus() {
        return (fileType == C_PLUS_PLUS);
    }
    
    public boolean isJava() {
        return (fileType == JAVA);
    }
    
    public boolean isRuby() {
        return (fileType == RUBY);
    }
    
    /**
     * FIXME: this should be replaced with a proper system of Mode,
     * incorporating Indenter and all the other stuff currently done
     * by asking isRuby and isJava et cetera.
     */
    public String getFileType() {
        return fileType;
    }
    
    public void addContextSpecificMenuItems(Collection items) {
        boolean needSeparator = true;
    }
    
    public void goToLine(int line) {
        try {
            // Humans number lines from 1, JTextComponent from 0.
            line--;
            final int start = text.getLineStartOffset(line);
            final int end = text.getLineEndOffset(line);
            JTextComponentUtilities.goToSelection(text, start, end);
        } catch (javax.swing.text.BadLocationException ex) {
            ex.printStackTrace();
        }
    }
    
    public int getCurrentLineNumber() {
        try {
            // Humans number lines from 1, JTextComponent from 0.
            return 1 + text.getLineOfOffset(text.getCaretPosition());
        } catch (BadLocationException ex) {
            return 0;
        }
    }
    
    public boolean isDirty() {
        return isDirty;
    }
    
    public void markAsDirty() {
        findResultsUpdater.restart();
        isDirty = true;
        repaint();
    }
    
    public void markAsClean() {
        isDirty = false;
        repaint();
    }
    
    public void clear() {
        text.setText("");
    }
    
    public void findNext() {
        updateFindResults();
        JTextComponentUtilities.findNextHighlight(getText(), Position.Bias.Forward, FindAction.PAINTER);
    }
    
    public void findPrevious() {
        updateFindResults();
        JTextComponentUtilities.findNextHighlight(getText(), Position.Bias.Backward, FindAction.PAINTER);
    }
    
    public void updateFindResults() {
        FindAction.INSTANCE.repeatLastFind(this);
    }
    
    public boolean isFocusCycleRoot() {
        return true;
    }
    
    /**
     * Returns the offset corresponding to an actual start offset and an emacs line-relative offset.
     * Emacs, it seems, although it allows the user to alter the tab size, talks to the outside world
     * in terms of character cells rather than characters, and takes a tab to be eight cells (and all
     * other characters to be a single cell). So we, who use characters throughout, need to
     * translate emacs offsets into real offsets.
     */
    private int emacsWalk(int offset, int howFar) {
        try {
            while (--howFar > 0) {
                char c = text.getCharAt(offset);
                if (c == '\t') howFar -= 7;
                offset++;
            }
        } catch (javax.swing.text.BadLocationException ex) {
            ex.printStackTrace();
        }
        return offset;
    }
    
    /**
     * Returns the distance between the given offset and the given line-start offset
     * in emacs' corrupted tabs-are-eight-spaces-and-not-characters-in-their-own-right
     * terms. Damn that program to hell.
     */
    private int emacsDistance(int pureCaretOffset, int lineStart) {
        int result = 0;
        try {
            for (int offset = lineStart; offset < pureCaretOffset; offset++) {
                char c = text.getCharAt(offset);
                if (c == '\t') result += 7;
                result++;
            }
        } catch (javax.swing.text.BadLocationException ex) {
            ex.printStackTrace();
        }
        return result;
    }
    
    public void jumpToAddress(String address) {
        try {
            StringTokenizer st = new StringTokenizer(address, ":");
            int line = Integer.parseInt(st.nextToken()) - 1;
            int offset = text.getLineStartOffset(line);
            int maxOffset = text.getLineEndOffset(line) - 1;
            if (st.hasMoreTokens()) {
                try {
                    offset = emacsWalk(offset, Integer.parseInt(st.nextToken()));
                } catch (NumberFormatException ex) {
                    ex = ex;
                }
            }
            
            // We interpret address ending with a ":" (from grep and compilers) as requiring
            // the line to be selected. Other addresses (such as from our open-file-list) just
            // mean "position the caret".
            int endOffset = (address.endsWith(":")) ? (maxOffset + 1) : offset;
            
            if (st.hasMoreTokens()) {
                try {
                    endOffset = text.getLineStartOffset(Integer.parseInt(st.nextToken()) - 1);
                } catch (NumberFormatException ex) {
                    ex = ex;
                }
            }
            if (st.hasMoreTokens()) {
                try {
                    // emacs end offsets seem to include the character following.
                    endOffset = emacsWalk(endOffset, Integer.parseInt(st.nextToken())) + 1;
                } catch (NumberFormatException ex) {
                    ex = ex;
                }
            }
            offset = Math.min(offset, maxOffset);
            endOffset = Math.min(endOffset, maxOffset);
            JTextComponentUtilities.goToSelection(text, offset, endOffset);
        } catch (javax.swing.text.BadLocationException ex) {
            ex.printStackTrace();
        }
    }
    
    /**
    * Returns the name of the context for this window. In this case, the directory the file's in.
    */
    public String getContext() {
        return FileUtilities.getUserFriendlyName(file.getParent());
    }
    
    public void reportError(String error) {
        getWorkspace().reportError(getContext(), error);
    }
    
    public boolean isOutOfDateWithRespectToDisk() {
        if (file.exists() == false) {
            return false;
        }
        
        // If the time stamp on disk is the same as it was when we last read
        // or wrote the file, assume it hasn't changed.
        if (file.lastModified() == lastModifiedTime) {
            return false;
        }
        
        // If the on-disk content is the same as what we have in memory, then
        // the fact that the time stamp is different isn't significant.
        try {
            String currentContentInMemory = getText().getText();
            String currentContentOnDisk = StringUtilities.readFile(file);
            if (currentContentInMemory.equals(currentContentOnDisk)) {
                return false;
            }
        } catch (Exception ex) {
            Log.warn("Couldn't compare with on-disk copy.", ex);
        }
        
        return true;
    }
    
    /** Saves the text. Returns true if the file was saved okay. */
    public boolean save() {
        if (isOutOfDateWithRespectToDisk()) {
            //TODO: report when the file was modified & when your copy dates from.
            boolean replace = Edit.askQuestion("Save", "The file '" + this.filename + "' has been modified since it was read in. Do you want to replace it with the version you are saving?", "Replace");
            if (replace == false) {
                return false;
            }
        }
        
        if (file.exists() && copyFile(this.filename, this.filename + ".bak") == false) {
            Edit.showAlert("Save", "File '" + this.filename + "' wasn't saved! Couldn't create backup file.");
            return false;
        }
        
        try {
            Edit.showStatus("Saving " + filename + "...");
            writeCopyTo(file);
            Edit.showStatus("Saved " + filename);
            markAsClean();
            this.lastModifiedTime = file.lastModified();
            tagsUpdater.updateTags();
            return true;
        } catch (IOException ex) {
            Edit.showStatus("");
            Edit.showAlert("Save", "Couldn't save file '" + filename + "' (" + ex.getMessage() + ").");
            ex.printStackTrace();
        }
        return false;
    }
    
    /** Saves the text to a file with the given name. Returns true if the file was saved okay. */
    public boolean saveAs(String newFilename) {
        try {
            File newFile = FileUtilities.fileFromString(newFilename);
            if (newFile.exists()) {
                boolean replace = Edit.askQuestion("Save As", "An item named '" + newFilename + "' already exists in this location. Do you want to replace it with the one you are saving?", "Replace");
                if (replace == false) {
                    return false;
                }
            }
            writeCopyTo(newFile);
            return true;
        } catch (Exception ex) {
            Edit.showAlert("Save As", "Couldn't save file '" + newFilename + "' (" + ex.getMessage() + ").");
            ex.printStackTrace();
        }
        return false;
    }
    
    public void writeCopyTo(File file) throws IOException {
        Writer writer = new BufferedWriter(new FileWriter(file));
        text.write(writer);
        writer.close();
    }
    
    /** FIXME: replace with use of File.renameTo */
    public boolean copyFile(String fromName, String toName) {
        try {
            File fromFile = FileUtilities.fileFromString(fromName);
            File toFile = FileUtilities.fileFromString(toName);
            FileInputStream from = new FileInputStream(fromFile);
            FileOutputStream to = new FileOutputStream(toFile);
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = from.read(buffer)) != -1) {
                to.write(buffer, 0, bytesRead);
            }
            to.close();
            from.close();
            return true;
        } catch (IOException ex) {
            ex.printStackTrace();
            return false;
        }
    }
    
    public void invokeShellCommand(String context, String command, boolean shouldShowProgress) {
        try {
            new ShellCommand(filename, getCurrentLineNumber(), getWorkspace(), shouldShowProgress, context, command);
        } catch (IOException ex) {
            Edit.showAlert("Run", "Can't start task (" + ex.getMessage() + ").");
        }
    }
}
