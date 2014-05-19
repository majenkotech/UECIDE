/*
 * Copyright (c) 2014, Majenko Technologies
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * 
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 * 
 * * Redistributions in binary form must reproduce the above copyright notice, this
 *   list of conditions and the following disclaimer in the documentation and/or
 *   other materials provided with the distribution.
 * 
 * * Neither the name of Majenko Technologies nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package uecide.app;

import uecide.app.debug.*;
import uecide.app.preproc.*;
import uecide.app.editors.*;
import uecide.plugin.*;

import java.util.regex.*;

import jssc.*;

import java.awt.*;
import java.awt.event.*;
import java.beans.*;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.jar.*;
import java.util.zip.*;
import java.text.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import uecide.app.debug.Compiler;

/**
 * Stores information about files in the current sketch
 */
public class Sketch implements MessageConsumer {
    public String sketchName;     // The name of the sketch
    public File sketchFolder;
    public Editor editor = null;
    public File buildFolder;
    public String uuid; // used for temporary folders etc

    boolean isUntitled;

    Writer stdoutRedirect = null;
    Writer stderrRedirect = null;

    Core selectedCore = null;
    Board selectedBoard = null;
    Compiler selectedCompiler = null;
    String selectedProgrammer = null;
    String selectedSerialPort = null;

    public ArrayList<File> sketchFiles = new ArrayList<File>();

    public HashMap<String, Library> importedLibraries = new HashMap<String, Library>();
    public ArrayList<Library> orderedLibraries = new ArrayList<Library>();

    public HashMap<String, String> settings = new HashMap<String, String>();
    public HashMap<String, String> parameters = new HashMap<String, String>();

    HashMap<String, String> selectedOptions = new HashMap<String, String>();

    public boolean doPrePurge = false;

    /**************************************************************************
     * CONSTRUCTORS                                                           *
     *                                                                        *
     * The Sketch takes a file, be that as a string or a File object, as the  *
     * only parameter.  The sketch is loaded either from the provided folder, *
     * or the folder the provided file resides in.                            *
     **************************************************************************/

    public Sketch(String path) {
        this(new File(path));
    }

    public Sketch(File path) {
        uuid = UUID.randomUUID().toString();

        isUntitled = false;
        if (path == null) {
            path = createUntitledSketch();
        }
        sketchFolder = path;
        if (!path.exists()) {
            path.mkdirs();
            createBlankFile(path.getName() + ".ino");
        }

        String fn = path.getName().toLowerCase();
        if (fn.endsWith(".ino") || fn.endsWith(".pde")) {
            path = path.getParentFile();
        }
        sketchFolder = path;
        sketchName = sketchFolder.getName();
        loadSketchFromFolder(path);
        buildFolder = createBuildFolder();
    }

    /**************************************************************************
     * OBJECTS                                                                *
     *                                                                        *
     * Internal object management.  All the routines to deal with boards,     *
     * cores, compilers, etc, and what happens when you switch from one to    *
     * another.                                                               *
     **************************************************************************/

    public Board getBoard() {
        return selectedBoard;
    }

    public void setBoard(String board) {
        Board b = Base.getBoard(board);
        setBoard(b);
    }

    public void setBoard(Board board) {
        if (board == null) {
            return;
        }
        selectedBoard = board;
        Base.preferences.set("board", board.getName());
        Core core = Base.cores.get(Base.preferences.get("board." + selectedBoard.getName() + ".core"));
        if (core == null) {
            core = board.getCore();
        }
        if (core != null) {
            setCore(core);
        }
    }

    public Core getCore() {
        return selectedCore;
    }

    public void setCore(String core) {
        Core c = Base.getCore(core);
        setCore(c);
    }

    public void setCore(Core core) {
        if (core == null) {
            return;
        }
        selectedCore = core;
        Base.preferences.set("board." + selectedBoard.getName() + ".core", core.getName());
        Compiler compiler = Base.compilers.get(Base.preferences.get("board." + selectedBoard.getName() + ".compiler"));
        if (compiler == null) {
            compiler = core.getCompiler();
        }
        if (compiler != null) {
            setCompiler(compiler);
        }
    }

    public Compiler getCompiler() {
        return selectedCompiler;
    }

    public void setCompiler(String compiler) {
        Compiler c = Base.getCompiler(compiler);
        setCompiler(c);
    }

    public void setCompiler(Compiler compiler) {
        if (compiler == null) {
            return;
        }
        selectedCompiler = compiler;
        Base.preferences.set("board." + selectedBoard.getName() + ".compiler", compiler.getName());
        String programmer = Base.preferences.get("board." + selectedBoard.getName() + ".programmer");
        if (programmer == null) {
            HashMap<String, String> pl = getProgrammerList();
            for (String p : pl.keySet()) {
                programmer = p;
                break;
            }
        }
        setProgrammer(programmer);
    }

    public String getProgrammer() {
        return selectedProgrammer;
    }

    public HashMap<String, String> getProgrammerList() {
        PropertyFile props = mergeAllProperties();
        HashMap<String, String> out = new HashMap<String, String>();

        String[] spl = props.getArray("sketch.upload");
        Arrays.sort(spl);
        for (String pn : spl) {
            String name = props.get("upload." + pn + ".name");
            out.put(pn, name);
        }
        return out;
    }

    public void setProgrammer(String programmer) {
        if (programmer == null) {
            return;
        }
        Base.preferences.set("board." + selectedBoard.getName() + ".programmer", programmer);
        selectedProgrammer = programmer;
        Base.preferences.save();
        if (editor != null) editor.updateAll();
    }

    public String getSerialPort() {
        return selectedSerialPort;
    }

    public void setSerialPort(String p) {
        selectedSerialPort = p;
        if (editor != null) editor.updateAll();
    }

    void attachToEditor(Editor e) {
        editor = e;
        editor.setTitle(Base.theme.get("product.cap") + " | " + sketchName);
    }



    /**************************************************************************
     * FILE HANDLING                                                          *
     *                                                                        *
     * These are all routines to deal with files - file reading, writing,     *
     * copying, etc.                                                          *
     **************************************************************************/

    public void createNewFile(String filename) {
        File f = createBlankFile(filename);
        sketchFiles.add(f);
        if (editor != null) {
            editor.updateTree();
            editor.openOrSelectFile(f);
        }
    }

    public File createBlankFile(String fileName) {
        File f = new File(sketchFolder, fileName);
        if (f.exists()) {
            return f;
        }
        try {
            f.createNewFile();
        } catch (Exception e) {
        }
        return f;
    }

    public void loadSketchFromFolder(File folder) {
        sketchFolder = folder;
        if (!isUntitled()) {
            Base.updateMRU(sketchFolder);
        }
        File fileList[] = sketchFolder.listFiles();
        Arrays.sort(fileList);

        for (File f : fileList){
            switch(FileType.getType(f)) {
                case FileType.SKETCH:
                case FileType.CSOURCE:
                case FileType.CPPSOURCE:
                case FileType.ASMSOURCE:
                case FileType.HEADER:
                    loadFile(f);
            }
        }

        setBoard(Base.preferences.get("board"));
        setSerialPort(Base.preferences.get("serial.port"));
        updateLibraryList();
    }

    public boolean loadFile(File f) {
        if (!f.exists()) {
            return false;
        }

        sketchFiles.add(f);
        return true;
    }

    public File getFileByName(String filename) {
        for (File f : sketchFiles) {
            if (f.getName().equals(filename)) {
                return f;
            }   
        }
        return null;
    }

    public String[] getFileNames() {
        String[] out = new String[sketchFiles.size()];
        int i = 0;
        for (File f : sketchFiles) {
            out[i++] = f.getName();
        }
        Arrays.sort(out);
        return out;
    }

    public File createBuildFolder() {
        String name = "build-" + uuid;
        Debug.message("Creating build folder " + name);
        File f = new File(Base.getTmpDir(), name);
        if (!f.exists()) {
            f.mkdirs();
        }
        f.deleteOnExit();
        return f;
    }

    public File createUntitledSketch() {
        int num = 0;
        isUntitled = true;
        File f = null;
        do {
            num++;
            String name = "untitled" + Integer.toString(num);
            f = new File(Base.getTmpDir(), name);
        } while (f.exists());
        f.deleteOnExit();
        return f;
    }

    public File getMainFile() {
        File f = getFileByName(sketchName + ".ino");
        if (f == null) {
            f = getFileByName(sketchName + ".pde");
        }
        return f;
    }

    public String getMainFilePath() {
        File f = getMainFile();
        if (f == null) {
            return "";
        }
        return f.getAbsolutePath();
    }

    public boolean isModified() {
        // If we have no editor then it's impossible for a file
        // to be modified.
        if (editor == null) {
            return false;
        }

        boolean modified = false;
        int tabs = editor.getTabCount();
        for (int tab = 0; tab < tabs; tab++) {
            EditorBase ed = editor.getTab(tab);
            if (ed.isModified()) {
                modified = true;
            }
        }
        return modified;
    }

    static public boolean isSanitaryName(String name) {
        return sanitizeName(name).equals(name);
    }

    static public String sanitizeName(String origName) {
        char c[] = origName.toCharArray();
        StringBuffer buffer = new StringBuffer();

        for (int i = 0; i < c.length; i++) {
            if (((c[i] >= '0') && (c[i] <= '9')) ||
                ((c[i] >= 'a') && (c[i] <= 'z')) ||
                ((c[i] >= 'A') && (c[i] <= 'Z'))) {
                buffer.append(c[i]);
            } else {
                buffer.append('_');
            }
        }
        if (buffer.length() > 63) {
            buffer.setLength(63);
        }
        return buffer.toString();
    }

    public File getFolder() {
        return sketchFolder;
    }


    // Get the content of a file.  If it's open in the editor then
    // return the content of the editor, otherwise load the data from
    // the file.
    public String getFileContent(File f) { return getFileContent(f, false); }
    public String getFileContent(File f, boolean forceOffDisk) {
        if (!f.exists()) {
            return "";
        }
        if (f.isDirectory()) {
            return "";
        }
        if (!forceOffDisk) {
            if (editor != null) {
                int tabNumber = editor.getTabByFile(f);
                if (tabNumber > -1) {   
                    EditorBase eb = editor.getTab(tabNumber);
                    return eb.getText();
                }
            }
        }
        InputStream    fis;
        BufferedReader br;
        String         line;
        StringBuilder sb = new StringBuilder();

        try {

            fis = new FileInputStream(f);
            br = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            while ((line = br.readLine()) != null) {
                sb.append(line + "\n");
            }
            br.close();
        } catch (Exception e) {
            error(e);
        }
        return sb.toString();
    }

    // Write the content of a file to a folder.
    public void writeFileToFolder(File file, File folder) {
        File dest = new File(folder, file.getName());
        try {
            PrintWriter out = new PrintWriter(dest);
            out.print(getFileContent(file));
            out.close();
        } catch (Exception e) {
            error(e);
        }
    }

    // Write out any locally cached copies of files (in editors)
    // to the disk.
    public void saveAllFiles() {
        // If we don't have an editor then we don't have any cached files.
        if (editor == null) {
            return;
        }

        for (File f : sketchFiles) {
            int tab = editor.getTabByFile(f);
            if (tab > -1) {
                EditorBase eb = editor.getTab(tab);
                if (eb.isModified()) {
                    eb.setModified(false);
                    writeFileToFolder(f, sketchFolder);
                }
            }
        }
        Base.updateMRU(sketchFolder);
        if (editor != null) { editor.updateMenus(); }
    }

    /**************************************************************************
     * COMPILATION                                                            *
     *                                                                        *
     * This section deals with the compilation of the sketch.  It has all the *
     * routines needed to convert a sketch into C++ or C source code, and to  *
     * compile, link, and archive it all.                                     *
     **************************************************************************/

    HashMap<File, String> cleanedFiles;

    public boolean cleanFiles() {
        cleanedFiles = new HashMap<File, String>();
        for (File f : sketchFiles) {
            if (FileType.getType(f) == FileType.SKETCH) {
                String data = getFileContent(f);
                String cleanData = stripComments(data);
                String[] lines = cleanData.split("\n");
                int lineno = 1;
                StringBuilder out = new StringBuilder();
                for (String line : lines) {
                    line = line.trim();
                    out.append(line + "\n");
                }
                cleanedFiles.put(f, out.toString());
            } else {
                cleanedFiles.put(f, getFileContent(f));
            }
        }
        return true;
    }

    public String stripBlock(String in, String start, String end) {
        String regexp;
        String mid;
        if (start.equals(end)) {
            mid = start;
        } else {
            mid = start + end;
        }
        if (start == "{") start = "\\" + start;
        if (end == "}") end = "\\" + end;
        regexp = "(?s)" + start + "[^" + mid + "]*" + end;

        boolean done = false;
        String out = in;
        int pass = 1;
        while (!done) {
            String rep = out.replaceAll(regexp, "");
            if (rep.equals(out)) {
                done = true;
            } else {
                out = rep;
            }
            pass++;
        }
        return out;
    }

    // Locate a library by its header file.  Scan through all the known 
    // libraries for the right file.  First pass looks for libraries named
    // after the included file.  Second pass looks through all the
    // libraries for that include file.
    public Library findLibrary(String filename) {
        int dot = filename.lastIndexOf(".");

        String trimmedName = filename;
        if (dot >= 0) {
            trimmedName = filename.substring(0, dot);
        }

        // If the include file is part of the sketch, then we're not
        // interested in it.

        if (getFileByName(filename) != null) {
            return null;
        }


        // First look in the sketch libs folder if it exists

        File libfolder = getLibrariesFolder();
        if (libfolder.exists() && libfolder.isDirectory()) {
            File[] files = libfolder.listFiles();
            for (File f : files) {
                if (f.getName().equals(trimmedName)) {
                    File testFile = new File(f, filename);
                    if (f.exists()) {
                        return new Library(testFile, trimmedName, "sketch");
                    }
                }
            }
        }


        // Now scan through all the collections, starting with the
        // board, then the core, then the compiler, and finally any
        // third party libraries.


        HashMap<String, Library> boardLibraries = Base.getLibraryCollection("board:" + getBoard().getName(), getCore().getName());
        HashMap<String, Library> coreLibraries = Base.getLibraryCollection("core:" + getCore().getName(), getCore().getName());
        HashMap<String, Library> compilerLibraries = Base.getLibraryCollection("compiler:" + getCompiler().getName(), getCore().getName());
        HashMap<String, Library> sketchbookLibraries = Base.getLibraryCollection("sketch:all", getCore().getName());

        if (boardLibraries.get(trimmedName) != null) {
            return boardLibraries.get(trimmedName);
        }

        if (coreLibraries.get(trimmedName) != null) {
            return coreLibraries.get(trimmedName);
        }

        if (compilerLibraries.get(trimmedName) != null) {
            return compilerLibraries.get(trimmedName);
        }

        for (String key : Base.libraryCategoryNames.keySet()) {
            HashMap<String, Library> catLib = Base.getLibraryCollection("cat:" + key, getCore().getName());
            Library lib = catLib.get(trimmedName);
            if (lib != null) {
                return lib;
            }
        }

        if (sketchbookLibraries.get(trimmedName) != null) {
            return sketchbookLibraries.get(trimmedName);
        }

        // TODO: If we get to here then we have not found an exact match.  Let's do the long-winded scan of all the files then.


        return null;
    }

    public String findFunctions(String in) {
        String out = in.replaceAll("\\\\.", "");

        out = out.replaceAll("'[^'\\n\\r]*'", "");
        out = out.replaceAll("\"[^\"\\n\\r]*\"", "");
        out = stripBlock(out, "{", "}");
        String[] s = out.split("\n");
        StringBuilder decimated = new StringBuilder();
        for (String line : s) {
            line = line.trim();
            if (line.endsWith(";")) {
                continue;
            }
            if (line.equals("")) {
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            if (line.indexOf("(") == -1) {
                continue;
            }
            Pattern p = Pattern.compile("[a-zA-Z0-9_]+\\s+[a-zA-Z0-9_]+\\s*\\(");
            Matcher m = p.matcher(line);
            if (m.find()) {
                decimated.append(line + "\n");
            }
        }
        return decimated.toString();
    }

    public void updateLibraryList() {
        cleanFiles();
        HashMap<String, Integer> inclist = new HashMap<String, Integer>();
        Pattern inc = Pattern.compile("^#\\s*include\\s+[<\"](.*)[>\"]");
        for (File f : cleanedFiles.keySet()) {
            String data = cleanedFiles.get(f);
            String lines[] = data.split("\n");
            for (String line : lines) {
                Matcher match = inc.matcher(line.trim());
                if (match.find()) {
                    inclist.put(match.group(1), 0);
                }
            }
        }

        importedLibraries = new HashMap<String, Library>();

        int processed = 0;
        do {
            HashMap<String, Integer> newinclist = new HashMap<String, Integer>();
            processed = 0;
            for (String incfile : inclist.keySet()) {
                if (inclist.get(incfile) == 1) {
                    newinclist.put(incfile, 1);
                    continue;
                }
                inclist.put(incfile, 1);
                Library lib = findLibrary(incfile);
                if (lib == null) {
                    newinclist.put(incfile, 2);
                    continue;
                }
                importedLibraries.put(lib.getName(), lib);
                newinclist.put(incfile, 1);
                ArrayList<String> req = lib.getRequiredLibraries();
                for (String r : req) {
                    if (inclist.get(r) == null) {
                        newinclist.put(r, 0);
                    } else {
                        newinclist.put(r, 1);
                    }
                }
                processed++;
            }
            inclist.clear();
            inclist.putAll(newinclist);
            for (String i : newinclist.keySet()) {
            }
        } while (processed != 0);

        if (editor != null) {
            editor.updateLibrariesTree();
        }
    }

    public boolean prepare() {
        PropertyFile props = mergeAllProperties();

        if (getBoard() == null) {
            error(Translate.w("You have no board selected.  You must select a board before you can compile your sketch.", 80, "\n"));
            return false;
        }
        if (getCore() == null) {
            error(Translate.w("You have no core selected.  You must select a core before you can compile your sketch.", 80, "\n"));
            return false;
        }
        if (Base.preferences.getBoolean("export.delete_target_folder")) {
            cleanBuild();
        }

        updateLibraryList();

        // We now have the data.  Now, if we're combining files, we shall do it
        // in this map.

        if (Base.preferences.getBoolean("compiler.combine_ino")) {
            File mainFile = getMainFile();
            StringBuilder out = new StringBuilder();

            out.append("#line 1 \"" + mainFile.getName() + "\"\n");
            out.append(cleanedFiles.get(mainFile));

            for (String fn : getFileNames()) {
                File f = getFileByName(fn);
                if (FileType.getType(f) == FileType.SKETCH) {
                    if (f != mainFile) {
                        String data = cleanedFiles.get(f);
                        out.append("#line 1 \"" + f.getName() + "\"\n");
                        out.append(data);
                        cleanedFiles.remove(f);
                    }
                }
            }
            cleanedFiles.put(mainFile, out.toString());
        }
    
        // We now have a has of all the file data, with any ino or pde
        // files combined into the main file entry.  The old ino and pde
        // entries have been removed.  Now to parse any INO or PDE files
        // for prototypes etc and do the Arduino fudging.  We should 
        // do it properly, really, looking for the first function in the
        // file and placing the prototypes directly before that.

        Pattern pragma = Pattern.compile("^#pragma\\s+parameter\\s+([^=]+)\\s*=\\s*(.*)\\s*$");
        parameters = new HashMap<String, String>();
        for (File f : cleanedFiles.keySet()) {
            if (FileType.getType(f) == FileType.SKETCH) {
                String functions = findFunctions(cleanedFiles.get(f));
        
                String[] s = functions.split("\n");
                String firstFunction = s[0];
                if (firstFunction.trim().equals("")) {
                    continue;
                }
                int line = 1;
                StringBuilder munged = new StringBuilder();
                for (String l : cleanedFiles.get(f).split("\n")) {
                    if (l.trim().startsWith(firstFunction)) {
                        for (String func : s) {
                            munged.append(func + ";\n");
                        }
                        munged.append("#line " + line + " \"" + f.getName() + "\"\n");
                    }
                    Matcher mtch = pragma.matcher(l.trim());
                    if (mtch.find()) {
                        l = "// " + l;
                        parameters.put(mtch.group(1), mtch.group(2));
                    }
                    munged.append(l + "\n");
                    if (!l.startsWith("#line 1 ")) {
                        line++;
                    }
                    cleanedFiles.put(f, munged.toString());
                }
            }
        }

        // Now we have munged the files, let's dump them into files in the
        // build folder ready for compilation.

        try {
            for (File f : cleanedFiles.keySet()) {
                if (FileType.getType(f) == FileType.SKETCH) {
                    String ext = props.get("build.extension");
                    if (ext == null) {
                        ext = "cpp";
                    }
                    String newFileName = f.getName().substring(0, f.getName().lastIndexOf(".")) + "." + ext;
                    File newFile = new File(buildFolder, newFileName);
                    PrintWriter pw = new PrintWriter(newFile);
                    pw.write("/***************************************************\n");
                    pw.write(" * ! ! ! !        I M P O R T A N T        ! ! ! ! *\n");
                    pw.write(" *                                                 *\n");
                    pw.write(" * THIS FILE IS AUTOMATICALLY GENERATED AND SHOULD *\n");
                    pw.write(" * NOT BE DIRECTLY EDITED. INSTEAD EDIT THE INO OR *\n");
                    pw.write(" * PDE FILE THIS FILE IS GENERATED FROM!!!         *\n");
                    pw.write(" ***************************************************/\n");
                    pw.write("\n");
                    if (props.get("core.header") != null) {
                        pw.write("#include <" + props.get("core.header") + ">\n");
                    }
                    if (!Base.preferences.getBoolean("compiler.combine_ino")) {
                        pw.write("#line 1 \"" + f.getName() + "\"\n");
                    }
                    pw.write(cleanedFiles.get(f));
                    pw.close();
                } else {
                    File buildFile = new File(buildFolder, f.getName());
                    PrintWriter pw = new PrintWriter(buildFile);
                    pw.write("/***************************************************\n");
                    pw.write(" * ! ! ! !        I M P O R T A N T        ! ! ! ! *\n");
                    pw.write(" *                                                 *\n");
                    pw.write(" * THIS FILE IS AUTOMATICALLY GENERATED AND SHOULD *\n");
                    pw.write(" * NOT BE DIRECTLY EDITED. INSTEAD EDIT THE SOURCE *\n");
                    pw.write(" * FILE THIS FILE IS GENERATED FROM!!!             *\n");
                    pw.write(" ***************************************************/\n");
                    pw.write("#line 1 \"" + f.getName() + "\"\n");
                    pw.write(cleanedFiles.get(f));
                    pw.close();
                }
            }
        } catch (Exception e) {
            error(e);
        }

        if (editor != null) {
            editor.updateOutputTree();
        }
        return true;
    }

    public String stripComments(String data) {
        StringBuilder b = new StringBuilder();

        // Single line comments, both styles

        String[] lines = data.split("\n");
        for (String line : lines) {
            int comment = line.indexOf("//");
            if (comment > -1) {
                line = line.substring(0, comment);
            }
            comment = line.indexOf("/*");
            int end = line.indexOf("*/");
            if (comment > -1 && end > comment) {
                line = line.substring(0, comment);
            }
            b.append(line);
            b.append("\n");
        }

        String out = b.toString();

        // Removing multi-line comments has to be done carefully.  We need to
        // preserve the right number of lines from the comment.

        lines = out.split("\n");

        b = new StringBuilder();
        
        boolean inComment = false;
        for (String line : lines) {
            if (!inComment) {
                int commentStart = line.indexOf("/*");
                if (commentStart > -1) {
                    line = line.substring(0, commentStart);
                    inComment = true;
                    b.append(line + "\n");
                    continue;
                }
                b.append(line + "\n");
                continue;
            }
            int commentEnd = line.indexOf("*/");
            if (commentEnd > -1) {
                line = line.substring(commentEnd + 2);
                b.append(line + "\n");
                inComment = false;
                continue;
            }
            b.append("\n");
        }

        out = b.toString();

        return out;
    }

    public String[] gatherIncludes(File f) {
        String[] data = getFileContent(f).split("\n"); //stripComments(f.textArea.getText()).split("\n");
        ArrayList<String> includes = new ArrayList<String>();
    
        Pattern pragma = Pattern.compile("#pragma\\s+parameter\\s+([^=]+)\\s*=\\s*(.*)");

        for (String line : data) {
            line = line.trim();
            if (line.startsWith("#pragma")) {
                Matcher m = pragma.matcher(line);
                if (m.find()) {
                    String key = m.group(1);
                    String value = m.group(2);
                    String munged = "";
                    for (int i = 0; i < value.length(); i++) {

                        if (value.charAt(i) == '"') {
                            munged += '"';
                            i++;
                            while (value.charAt(i) != '"') {
                                munged += value.charAt(i++);
                            }
                            munged += '"';
                            continue;
                        }

                        if (value.charAt(i) == '\'') {
                            munged += '\'';
                            i++;
                            while (value.charAt(i) != '\'') {
                                munged += value.charAt(i++);
                            }
                            munged += '\'';
                            continue;
                        }

                        if (value.charAt(i) == ' ') {
                            munged += "::";
                            continue;
                        }

                        munged += value.charAt(i);
                    }
                    parameters.put(key, munged);
                }
                continue;
            }
            if (line.startsWith("#include")) {
                int qs = line.indexOf("<");
                if (qs == -1) {
                    qs = line.indexOf("\"");
                }
                if (qs == -1) {
                    continue;
                }
                qs++;
                int qe = line.indexOf(">");
                if (qe == -1) {
                    qe = line.indexOf("\"", qs);
                }
                String i = line.substring(qs, qe);
                addLibraryToImportList(i);
            }
        }
        return includes.toArray(new String[includes.size()]);
    }

    public Library addLibraryToImportList(String l) {
        if (l.endsWith(".h")) {
            l = l.substring(0, l.lastIndexOf("."));
        }

        Library lib;

        // First, let's look for libraries included in the sketch folder itself.  Those should take priority over
        // every other library in the system.

        File sketchLibFolder = new File(sketchFolder, "libraries");
        if (sketchLibFolder.exists() && sketchLibFolder.isDirectory()) {
            File libFolder = new File(sketchLibFolder, l);
            if (libFolder.exists() && libFolder.isDirectory()) {
                File libHeader = new File(libFolder, l + ".h");
                if (libHeader.exists()) {
                    lib = new Library(libHeader, "sketch", "all");
                    importedLibraries.put(l, lib);
                    orderedLibraries.add(lib);

                    // And then work through all the required libraries and add them.
                    ArrayList<String> requiredLibraries = lib.getRequiredLibraries();
                    for (String req : requiredLibraries) {
                        addLibraryToImportList(req);
                    }
                    return lib;
                }
            }
        }

        HashMap<String, Library> boardLibraries = Base.getLibraryCollection("board:" + getBoard().getName(), getCore().getName());
        HashMap<String, Library> coreLibraries = Base.getLibraryCollection("core:" + getCore().getName(), getCore().getName());
        HashMap<String, Library> compilerLibraries = Base.getLibraryCollection("compiler:" + getCompiler().getName(), getCore().getName());
        HashMap<String, Library> contribLibraries = Base.getLibraryCollection("sketchbook:all", getCore().getName());

        lib = importedLibraries.get(l);
        if (lib != null) {
            // The library has already been imported.  Do nothing
            return lib;
        }

        lib = coreLibraries.get(l);
        if (lib == null) {
            lib = contribLibraries.get(l);
        }

        if (lib == null) {
            for (String key : Base.libraryCategoryNames.keySet()) {
                HashMap<String, Library> catLib = Base.getLibraryCollection("cat:" + key, getCore().getName());
                lib = catLib.get(l);
                if (lib != null) {
                    break;
                }
            }
        }

        if (lib == null) {
            // The library doesn't exist - either it's a system header or a library that isn't installed.
            return null;
        }

        // At this point we have a valid library that hasn't yet been imported.  Now to recurse.
        // First add the library to the imported list
        importedLibraries.put(l, lib);
        orderedLibraries.add(lib);

        // And then work through all the required libraries and add them.
        ArrayList<String> requiredLibraries = lib.getRequiredLibraries();
        for (String req : requiredLibraries) {
            addLibraryToImportList(req);
        }
        return lib;
    }

    public boolean upload() {
        String uploadCommand;
        if (!build()) {
            return false;
        }


        PropertyFile props = mergeAllProperties();
        String mess = props.get("upload." + getProgrammer() + ".message");
        if (mess != null) {
            message(mess);
        }
        message(Translate.t("Uploading to Board..."));
        settings.put("filename", sketchName);
        settings.put("filename.elf", sketchName + ".elf");
        settings.put("filename.hex", sketchName + ".hex");
        settings.put("filename.eep", sketchName + ".eep");

        boolean isJava = true;
        uploadCommand = getBoard().get("upload." + getProgrammer() + ".command.java");
        if (uploadCommand == null) {
            uploadCommand = getCore().get("upload." + getProgrammer() + ".command.java");
        }
        if (uploadCommand == null) {
            isJava = false;
            uploadCommand = getBoard().get("upload." + getProgrammer() + ".command." + Base.getOSFullName());
        }
        if (uploadCommand == null) {
            uploadCommand = getBoard().get("upload." + getProgrammer() + ".command." + Base.getOSName());
        }
        if (uploadCommand == null) {
            uploadCommand = getBoard().get("upload." + getProgrammer() + ".command");
        }
        if (uploadCommand == null) {
            uploadCommand = getCore().get("upload." + getProgrammer() + ".command." + Base.getOSFullName());
        }
        if (uploadCommand == null) {
            uploadCommand = getCore().get("upload." + getProgrammer() + ".command." + Base.getOSName());
        }
        if (uploadCommand == null) {
            uploadCommand = getCore().get("upload." + getProgrammer() + ".command");
        }

        if (uploadCommand == null) {
            error("No upload command defined for board");
            error(Translate.t("Upload Failed"));
            return false;
        }

   
        if (isJava) {
            Plugin uploader;
            uploader = Base.plugins.get(uploadCommand);
            if (uploader == null) {
                error("Upload class " + uploadCommand + " not found.");
                error(Translate.t("Upload Failed"));
                return false;
            }
            try {
                if ((uploader.flags() & BasePlugin.LOADER) == 0) {
                    error(uploadCommand + "is not a valid loader plugin.");
                    error(Translate.t("Upload Failed"));
                    return false;
                }
                uploader.run();
            } catch (Exception e) {
                error(Translate.t("Upload Failed"));
                error(e);
                return false;
            }
            message(Translate.t("Upload Complete"));
            return true;
        }

        String[] spl;
        spl = parseString(uploadCommand).split("::");

        String executable = spl[0];
        if (Base.isWindows()) {
            executable = executable + ".exe";
        }

        File exeFile = new File(sketchFolder, executable);
        File tools;
        if (!exeFile.exists()) {
            tools = new File(sketchFolder, "tools");
            exeFile = new File(tools, executable);
        }
        if (!exeFile.exists()) {
            exeFile = new File(getCore().getFolder(), executable);
        }
        if (!exeFile.exists()) {
            tools = new File(getCore().getFolder(), "tools");
            exeFile = new File(tools, executable);
        }
        if (!exeFile.exists()) {
            exeFile = new File(executable);
        }
        if (exeFile.exists()) {
            executable = exeFile.getAbsolutePath();
        }

        spl[0] = executable;

        // Parse each word, doing String replacement as needed, trimming it, and
        // generally getting it ready for executing.

        String commandString = executable;
        for (int i = 1; i < spl.length; i++) {
            String tmp = spl[i];
            tmp = tmp.trim();
            if (tmp.length() > 0) {
                commandString += "::" + tmp;
            }
        }

        boolean dtr = false;
        boolean rts = false;


        String ulu = props.get("upload." + getProgrammer() + ".using");
        if (ulu == null) ulu = "serial";

        String doDtr = props.get("upload." + getProgrammer() + ".dtr");
        if (doDtr != null) {
            if (doDtr.equals("yes")) {
                dtr = true;
            }
        }
        String doRts = props.get("upload." + getProgrammer() + ".rts");
        if (doRts != null) {
            if (doRts.equals("yes")) {
                rts = true;
            }
        }

        if (ulu.equals("serial") || ulu.equals("usbcdc"))
        {
            if (dtr || rts) {
                assertDTRRTS(dtr, rts);
            }
        }
        if (ulu.equals("usbcdc")) {
            try {
                String baud = props.get("upload." + getProgrammer() + ".reset.baud");
                if (baud != null) {
                    int b = Integer.parseInt(baud);
                    
                    SerialPort serialPort = Serial.requestPort(getSerialPort(), this, b);
                    if (serialPort == null) {
                        error("Unable to lock serial port");
                        return false;
                    }
                    Thread.sleep(1000);
                    Serial.releasePort(serialPort);
                    serialPort = null;
                    System.gc();
                    Thread.sleep(1500);
                }
            } catch (Exception e) {
                error(e);
            }
        }

        boolean res = execAsynchronously(commandString);
        if (ulu.equals("serial") || ulu.equals("usbcdc"))
        {
            if (dtr || rts) {
                assertDTRRTS(false, false);
            }
        }
        if (res) {
            message(Translate.t("Upload Complete"));
        } else {
            error(Translate.t("Upload Failed"));
        }
        return res;
    }

    public void assertDTRRTS(boolean dtr, boolean rts) {
        try {
            SerialPort serialPort = Serial.requestPort(getSerialPort(), this);
            if (serialPort == null) {
                error("Unable to lock serial port for board reset");
            }
            serialPort.setDTR(dtr);
            serialPort.setRTS(rts);
            Serial.releasePort(serialPort);
        } catch (Exception e) {
            error(e);
        }
        System.gc();
    }

    public boolean build() {
        if (Base.preferences.getBoolean("editor.external")) {
            //reloadAllFiles();
        }
        if (getBoard() == null) {
            error(Translate.w("The sketch cannot be compiled: You have no board selected. If you haven't yet installed any boards please do so through the Plugin Manager.", 80, "\n"));
            return false;
        }
        if (getCore() == null) {
            error(Translate.w("The sketch cannot be compiled: You have no core selected. If you haven't yet installed a core please do so through the Plugin Manager.", 80, "\n"));
            return false;
        }
        if (getCompiler() == null) {
            error(Translate.w("The sketch cannot be compiled: The compiler for the selected core is not available. Please ensure the compiler is installed using the Plugin Manager.", 80, "\n"));
            return false;
        }
        message(Translate.t("Compiling..."));
        try {
            if (!prepare()) {
                error(Translate.t("Compile Failed"));
                return false;
            }
        } catch (Exception e) {
            error(e);
            return false;
        }
        return compile();
    }

    public boolean saveAs(File newPath) {
        if (newPath.exists()) {
            return false;
        }
        newPath.mkdirs();
        File newMainFile = new File(newPath, newPath.getName() + ".ino");
        File oldMainFile = getMainFile();

        // First let's copy the contents of the existing sketch folder, renaming the main file.

        File[] files = sketchFolder.listFiles();
        for (File f : files) {
            if (f.equals(oldMainFile)) {
                Base.copyFile(f, newMainFile);
                continue;
            }
            File dest = new File(newPath, f.getName());
            if (f.isDirectory()) {
                Base.copyDir(f, dest);
                continue;
            }
            Base.copyFile(f, dest);
        }
        sketchFolder = newPath;
        sketchName = newPath.getName();
        isUntitled = false;
        // Now we can shuffle the files around in the sketchFiles array.
        // We want to try and keep the indexes in the same order if that
        // is possible, so we'll use a numeric iterator.

        ArrayList<File> newSketchFiles = new ArrayList<File>();
        for (int i = 0; i < sketchFiles.size(); i++) {
            File sf = sketchFiles.get(i);
            if (sf.equals(oldMainFile)) {
                newSketchFiles.add(i, newMainFile);
            } else {
                File newFile = new File(newPath, sf.getName());
                newSketchFiles.add(i, newFile);
            }
        }
        sketchFiles = newSketchFiles;

        // Now we have reconstructed the sketch in a new location we can save any
        // changed files from the editor.


        // One last thing we need to do is to rename the main file in the editor, if
        // it happens to be open.

        if (editor != null) {
            int mainTab = editor.getTabByFile(oldMainFile);
            if (mainTab > -1) {
                editor.setTabFile(mainTab, newMainFile);
            }
            editor.updateTree();
            editor.setTitle(Base.theme.get("product.cap") + " | " + sketchName);
        }

        save();
        return true;
    }

    public boolean save() {
        // We can't really save it if it's untitled - there's no point.
        if (isUntitled()) {
            return false;
        }
        if (Base.preferences.getBoolean("version.enabled")) {
            int numToSave = Base.preferences.getInteger("version.keep");
            File versionsFolder = new File(sketchFolder, "backup");
            if (!versionsFolder.exists()) {
                versionsFolder.mkdirs();
            }
            // Prune the oldest version if it exists
            File last = new File(versionsFolder, sketchName + "-" + numToSave);
            if (last.exists()) {
                Debug.message("Deleting " + last.getAbsolutePath());
                last.delete();
            }
            for (int i = numToSave-1; i >= 1; i--) {
                File low = new File(versionsFolder, sketchName + "-" + i);
                File high = new File(versionsFolder, sketchName + "-" + (i+1));
                if (low.exists()) {
                    Debug.message("Shuffling " + low.getAbsolutePath() + " to " + high.getAbsolutePath());
                    low.renameTo(high);
                }
            }
            File bottom = new File(versionsFolder, sketchName + "-1");
            Debug.message("Backing up as " + bottom.getAbsolutePath());
            bottom.mkdirs();
            for (File f : sketchFiles) {
                File to = new File(bottom, f.getName());
                Debug.message("    Backing up " + f.getAbsolutePath() + " to " + to.getAbsolutePath());
                Base.copyFile(f, to);
            }
        }
        saveAllFiles();
        return true;
    }

    public String getName() {
        return sketchName;
    }

    public Collection<Library> getImportedLibraries() {
        return importedLibraries.values();
    }

    public ArrayList<Library> getOrderedLibraries() {
        return orderedLibraries;
    }

    public ArrayList<String> getIncludePaths() {
        ArrayList<String> libFiles = new ArrayList<String>();

        PropertyFile props = mergeAllProperties();
      
        libFiles.add(buildFolder.getAbsolutePath());
        libFiles.add(getBoard().getFolder().getAbsolutePath());
        libFiles.add(getCore().getLibrariesFolder().getAbsolutePath());

        for (String key : props.childKeysOf("compiler.library")) {
            String coreLibName = key.substring(17);
            String libPaths = props.get(key);
            if (libPaths != null && !(libPaths.trim().equals(""))) {
                String[] libPathsArray = libPaths.split("::");
                for (String p : libPathsArray) {
                    File f = new File(getCompiler().getFolder(), p);
                    if (f.exists()) {
                        libFiles.add(f.getAbsolutePath());
                    }        
                }
            }
        }

        for (String key : props.childKeysOf("core.library")) {
            String coreLibName = key.substring(13);
            String libPaths = props.get(key);
            if (libPaths != null && !(libPaths.trim().equals(""))) {
                String[] libPathsArray = libPaths.split("::");
                for (String p : libPathsArray) {
                    File f = new File(getCore().getFolder(), p);
                    if (f.exists()) {
                        libFiles.add(f.getAbsolutePath());
                    }        
                }
            }
        }

        for (String key : props.childKeysOf("board.library")) {
            String coreLibName = key.substring(14);
            String libPaths = props.get(key);
            if (libPaths != null && !(libPaths.trim().equals(""))) {
                String[] libPathsArray = libPaths.split("::");
                for (String p : libPathsArray) {
                    File f = new File(getBoard().getFolder(), p);
                    if (f.exists()) {
                        libFiles.add(f.getAbsolutePath());
                    }        
                }
            }
        }

        for (Library l : getOrderedLibraries()) {
            libFiles.add(l.getFolder().getAbsolutePath());
        }
        return libFiles;
    }

    public void checkForSettings() {
        File mainFile = getMainFile();

        Pattern p = Pattern.compile("^#pragma\\s+parameter\\s+([^\\s]+)\\s*=\\s*(.*)$");

        String[] data = getFileContent(mainFile).split("\n");
        for (String line : data) {
            line = line.trim();
            Matcher m = p.matcher(line);
            if (m.find()) {
                String key = m.group(1);
                String value = m.group(2);
                if (key.equals("board")) {
                    setBoard(value);
                }
            }
        }
    }
        
    public File getCode(int i) {
        return sketchFiles.get(i);
    }

    public File getBuildFolder() {
        return buildFolder;
    }

    public String getBuildPath() {
        return buildFolder.getAbsolutePath();
    }

    public void cleanup() {
        System.gc();
        Base.removeDescendants(buildFolder);
    }

        public void setCompilingProgress(int percent) {
            if (editor != null) {
                editor.setProgress(percent);
            }
        }

    public boolean isReadOnly() {

        if (isInternal()) {
            return true;
        }

        File testFile = new File(sketchFolder, ".testWrite");
        boolean canWrite = false;
        try {
            testFile.createNewFile();
            if (testFile.exists()) {
                testFile.delete();
                canWrite = true;
            }
        } catch (Exception e) {
            return true;
        }

        if (!canWrite) {
            return true;
        }

        canWrite = true;

        for (File c : sketchFiles) {
            if (!c.canWrite()) {
                canWrite = false;
            }
        }
        return !canWrite;
    }

    public void redirectChannel(int c, Writer pw) {
        if (c <= 1) {
            stdoutRedirect = pw;
        }
        if (c == 2) {
            stderrRedirect = pw;
        }
    }

    public void unredirectChannel(int c) {
        if (c <= 1) {
            if (stdoutRedirect != null) {
                try {
                    stdoutRedirect.close();
                } catch (Exception e) {
                }
                stdoutRedirect = null;
            }
        }
        if (c == 2) {
            if (stderrRedirect != null) {
                try {
                    stderrRedirect.close();
                } catch (Exception e) {
                }
                stderrRedirect = null;
            }
        }
    }

    public void needPurge() {
        doPrePurge = true;
    }

    public String generateIncludes() {
        ArrayList<File> includes = new ArrayList<File>();

        HashMap<String, ArrayList<File>> coreLibs = getCoreLibs();
        for (String lib : coreLibs.keySet()) {
            ArrayList<File> libfiles = coreLibs.get(lib);
            includes.addAll(libfiles);
        }

        for (String libname : importedLibraries.keySet()) {
            includes.add(importedLibraries.get(libname).getFolder());
        }

        includes.add(getBoard().getFolder());
        includes.add(buildFolder);
        includes.add(sketchFolder);

        String includeList = "";
        for (File f : includes) {
            String path = f.getAbsolutePath();
            if (!(includeList.equals(""))) {
                includeList += "::";
            }
            includeList += "-I" + path;
        }
        return includeList;
    }

    public HashMap<String, ArrayList<File>> getCoreLibs() {
        PropertyFile props = mergeAllProperties();
        HashMap<String, ArrayList<File>> libs = new HashMap<String, ArrayList<File>>();

        for (String coreLibName : props.childKeysOf("compiler.library")) {
            ArrayList<File> files = new ArrayList<File>();
            String libPaths = props.get("compiler.library."+coreLibName);
            if (libPaths != null && !(libPaths.trim().equals(""))) {
                String[] libPathsArray = libPaths.split("::");
                for (String p : libPathsArray) {
                    File f = new File(getCompiler().getFolder(), p);
                    if (f.exists() && f.isDirectory()) {
                        files.add(f);
                    }
                }
                libs.put(coreLibName, files);
            }
        }

        for (String coreLibName : props.childKeysOf("core.library")) {
            ArrayList<File> files = new ArrayList<File>();
            String libPaths = props.get("core.library."+coreLibName);
            if (libPaths != null && !(libPaths.trim().equals(""))) {
                String[] libPathsArray = libPaths.split("::");
                for (String p : libPathsArray) {
                    File f = new File(getCore().getFolder(), p);
                    if (f.exists() && f.isDirectory()) {
                        files.add(f);
                    }
                }
                libs.put(coreLibName, files);
            }
        }
                
        for (String coreLibName : props.childKeysOf("board.library")) {
            ArrayList<File> files = new ArrayList<File>();
            String libPaths = props.get("board.library."+coreLibName);
            if (libPaths != null && !(libPaths.trim().equals(""))) {
                String[] libPathsArray = libPaths.split("::");
                for (String p : libPathsArray) {
                    File f = new File(getBoard().getFolder(), p);
                    if (f.exists() && f.isDirectory()) {
                        files.add(f);
                    }
                }
                libs.put(coreLibName, files);
            }
        }
        return libs;
    }

    public boolean compile() {

        PropertyFile props = mergeAllProperties();

        // Rewritten compilation system from the ground up.

        // Step one, we need to build a generic set of includes:

        settings.put("includes", generateIncludes());
        settings.put("filename", sketchName);

        if (doPrePurge) {
            doPrePurge = false;
            Base.removeDir(getCacheFolder());
        }

        settings.put("option.flags", getFlags("flags"));
        settings.put("option.cflags", getFlags("cflags"));
        settings.put("option.cppflags", getFlags("cppflags"));
        settings.put("option.ldflags", getFlags("ldflags"));

        // Copy any specified files from the compiler, core or board folders
        // into the build folder.  This is especially good if the compiler
        // executable needs certain DLL files to be in the current working
        // directory (which is the build folder).

        String precopy = parseString(props.get("compile.precopy"));
        if (precopy != null) {
            String[] copyfiles = precopy.split("::");
            for (String cf : copyfiles) {
                File src = new File(getCompiler().getFolder(), cf);
                if (!src.exists()) {
                    src = new File(getCore().getFolder(), cf);
                }
                if (!src.exists()) {
                    src = new File(getBoard().getFolder(), cf);
                }
                if (src.exists()) {
                    File dest = new File(buildFolder, src.getName());
                    Base.copyFile(src, dest);
                }
            }
        }

        message(Translate.t("Compiling Sketch..."));
        setCompilingProgress(10);
        ArrayList<File>sketchObjects = compileSketch();
        if (sketchObjects == null) {
            error("Failed compiling sketch");
            return false;
        }

        message(Translate.t("Compiling Core..."));
        setCompilingProgress(20);

        if (!compileCore()) {
            error("Failed compiling core");
            return false;
        }

        setCompilingProgress(30);

        message(Translate.t("Compiling Libraries..."));
        if (!compileLibraries()) {
            error("Failed compiling libraries");
            return false;
        }

        setCompilingProgress(40);

        message(Translate.t("Linking sketch..."));
        if (!compileLink(sketchObjects)) {
            error("Failed linking sketch");
            return false;
        }

        setCompilingProgress(50);
        if (!compileEEP()) {
            error("Failed extracting EEPROM image");
            return false;
        }

        setCompilingProgress(60);


        if (props.get("compile.lss") != null) {
            if (Base.preferences.getBoolean("compiler.generate_lss")) {
                File redirectTo = new File(buildFolder, sketchName + ".lss");
                if (redirectTo.exists()) {
                    redirectTo.delete();
                }

                boolean result = false;
                try {
                    redirectChannel(1, new PrintWriter(redirectTo));
                    result = compileLSS();
                    unredirectChannel(1);
                } catch (Exception e) {
                    result = false;
                }
                unredirectChannel(1);

                if (!result) {
                    error("Failed generating listing");
                    return false;
                }
                if (Base.preferences.getBoolean("export.save_lss")) {
                    try {
                        Base.copyFile(new File(buildFolder, sketchName + ".lss"), new File(sketchFolder, sketchName + ".lss"));
                    } catch (Exception e) {
                        error(e);
                    }
                }
            }
        }

        setCompilingProgress(70);
        if (!compileHEX()) {
            error("Failed converting to HEX filee");
            return false;
        }


        message(Translate.t("Compiling Done"));
        setCompilingProgress(100);
        if (editor != null) {
            editor.updateOutputTree();
        }
        return true;
    }

    public boolean compileLibraries() {
        for (String lib : importedLibraries.keySet()) {
            if (!compileLibrary(importedLibraries.get(lib))) {
                return false;
            }
        }
        return true;
    }

    public String parseString(String in)
    {
        int iStart;
        int iEnd;
        int iTest;
        String out;
        String start;
        String end;
        String mid;

        PropertyFile tokens = mergeAllProperties();

        out = in;

        if (out == null) {
            return null;
        }

        iStart = out.indexOf("${");
        if (iStart == -1) {
            return out;
        }

        iEnd = out.indexOf("}", iStart);
        iTest = out.indexOf("${", iStart+1);
        while ((iTest > -1) && (iTest < iEnd)) {
            iStart = iTest;
            iTest = out.indexOf("${", iStart+1);
        }

        while (iStart != -1) {
            start = out.substring(0, iStart);
            end = out.substring(iEnd+1);
            mid = out.substring(iStart+2, iEnd);

            if (mid.equals("compiler.root")) {
                mid = getCompiler().getFolder().getAbsolutePath();
            } else if (mid.equals("cache.root")) {
                mid = getCacheFolder().getAbsolutePath();
            } else if (mid.equals("core.root")) {
                mid = getCore().getFolder().getAbsolutePath();
            } else if (mid.equals("board.root")) {
                mid = getBoard().getFolder().getAbsolutePath();
            } else if ((mid.length() >8) && (mid.substring(0, 8).equals("replace:"))) {
                // replace:string,find,replace
                String[] bits = mid.substring(3).split(",");
                if (bits.length != 3) {
                    mid = "Syntax error in replace - bad arg count";
                } else {
                    mid = bits[0].replaceAll(bits[1], bits[2]);
                }
            } else if ((mid.length() >3) && (mid.substring(0, 3).equals("if:"))) {
                String[] bits = mid.substring(3).split(",");
                if (bits.length < 2 || bits.length > 3) {
                    mid = "Syntax Error in if - bad arg count";
                } else {
                    String condition = bits[0];
                    String trueVal = bits[1];
                    String falseVal = bits.length == 3 ? bits[2] : "";

                    String[] conditionBits = condition.split("=");
                    if (conditionBits.length != 2) {
                        mid = "Syntax Error in if - bad comparison";
                    } else {
                        String leftVal = conditionBits[0].trim();
                        String rightVal = conditionBits[1].trim();
                        if (leftVal.equals(rightVal)) {
                            mid = trueVal;
                        } else {
                            mid = falseVal;
                        }
                    }
                }
            } else if ((mid.length() >8) && (mid.substring(0, 8).equals("foreach:"))) {
                // ${foreach:${variable.name},pattern-to, replace with %0}
                String content = mid.substring(8);
                int commaPos = content.indexOf(',');
                if (commaPos > 0) {
                    String data = content.substring(0, commaPos);
                    String format = content.substring(commaPos + 1);
                    String[] each = data.split("::");
                    String outString = "";
                    for (String chunk : each) {
                        String ns = format.replaceAll("%0", chunk);
                        if (outString.equals("")) {
                            outString = ns;
                        } else {    
                            outString = outString + "::" + ns;
                        }
                    }
                    mid = outString;
                } else {
                    mid = "Syntax Error in foreach";
                }
            } else if ((mid.length() > 5) && (mid.substring(0,5).equals("find:"))) {
                // ${find:${path.list},${file.name}}
                String content = mid.substring(5);
                int commaPos = content.indexOf(',');
                if (commaPos > 0) {
                    String data = content.substring(0, commaPos);
                    String fname = content.substring(commaPos + 1);
                    String[] each = data.split("::");

                    String outString = "";
                    for (String chunk : each) {
                        File f = new File(chunk);
                        if (f.exists() && f.isDirectory()) {
                            File ff = new File(f, fname);
                            if (ff.exists()) {
                                mid = ff.getAbsolutePath();
                                break;
                            }
                        }
                    }
                } else {
                    mid = "Syntax Error in find";
                }
            } else if (mid.equals("verbose")) {
                if (Base.preferences.getBoolean("export.verbose")) 
                    mid = tokens.get("upload." + getProgrammer() + ".verbose");
                else 
                    mid = tokens.get("upload." + getProgrammer() + ".quiet");
            } else if (mid.equals("port.base")) {
                if (Base.isWindows()) {
                    mid = getSerialPort();
                } else {
                    String sp = getSerialPort();
                    mid = sp.substring(sp.lastIndexOf('/') + 1);
                }
            } else if (mid.equals("port")) {
                if (Base.isWindows()) {
                    mid = "\\\\.\\" + getSerialPort();
                } else {
                    mid = getSerialPort();
                }
            } else {
                String tmid = tokens.get(mid);
                if (tmid == null) {
                    tmid = "";
                }
                mid = tmid;
            }

            if (mid != null) {
                out = start + mid + end;
            } else {
                out = start + end;
            }
            iStart = out.indexOf("${");
            iEnd = out.indexOf("}", iStart);
            iTest = out.indexOf("${", iStart+1);
            while ((iTest > -1) && (iTest < iEnd)) {
                iStart = iTest;
                iTest = out.indexOf("${", iStart+1);
            }
        }

        // This shouldn't be needed as the methodology should always find any tokens put in
        // by other token replacements.  But just in case, eh?
        if (out != in) {
            out = parseString(out);
        }

        return out;
    }

    private File compileFile(File src) {

        String fileName = src.getName();
        String recipe = null;

        PropertyFile props = mergeAllProperties();

        if (fileName.endsWith(".cpp")) {
            recipe = props.get("compile.cpp");
        }
    
        if (fileName.endsWith(".cxx")) {
            recipe = props.get("compile.cpp");
        }
    
        if (fileName.endsWith(".cc")) {
            recipe = props.get("compile.cpp");
        }
    
        if (fileName.endsWith(".c")) {
            recipe = props.get("compile.c");
        }
    
        if (fileName.endsWith(".S")) {
            recipe = props.get("compile.S");
        }

        if (recipe == null) {
            message("Error: I don't know how to compile " + fileName);
            return null;
        }

        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
        File dest = new File(buildFolder, baseName + ".o");

        if (dest.exists()) {
            if (dest.lastModified() > src.lastModified()) {
                return dest;
            }
        }

        settings.put("build.path", buildFolder.getAbsolutePath());
        settings.put("source.name", src.getAbsolutePath());
        settings.put("object.name", dest.getAbsolutePath());

        String compiledString = parseString(recipe);

        if (!execAsynchronously(compiledString)) {
            return null;
        }
        if (!dest.exists()) {
            return null;
        }

        if (editor != null) {
            editor.updateOutputTree();
        }

        return dest;
    }

    public File getCacheFolder() {
        File cacheRoot = Base.getUserCacheFolder();
        File coreCache = new File(cacheRoot, getCore().getName());
        File boardCache = new File(coreCache, getBoard().getName());
        if (!boardCache.exists()) {  
            boardCache.mkdirs();
        }
        return boardCache;
    }

    public File getCacheFile(String fileName) {
        File cacheFolder = getCacheFolder();
        File out = new File(cacheFolder, fileName);
        return out;
    }

    public boolean compileCore() {
        HashMap<String, ArrayList<File>> coreLibs = getCoreLibs();

        for (String lib : coreLibs.keySet()) {
            if (!compileCore(coreLibs.get(lib), "Core_" + lib)) {
                return false;
            }
        }
        return true;
    }

    public boolean compileCore(ArrayList<File> core, String name) {
        File archive = getCacheFile("lib" + name + ".a");

        PropertyFile props = mergeAllProperties();
        String recipe = props.get("compile.ar");

        settings.put("library", archive.getAbsolutePath());

        long archiveDate = 0;
        if (archive.exists()) {
            archiveDate = archive.lastModified();
        }

        ArrayList<File> fileList = new ArrayList<File>();

        for (File f : core) {
            if (f.exists() && f.isDirectory()) {
                fileList.addAll(findFilesInFolder(f, "S", false));
                fileList.addAll(findFilesInFolder(f, "c", false));
                fileList.addAll(findFilesInFolder(f, "cpp", false));
                fileList.addAll(findFilesInFolder(f, "cxx", false));
                fileList.addAll(findFilesInFolder(f, "cc", false));
            }
        }

        for (File f : fileList) {
            if (f.lastModified() > archiveDate) {
                File out = compileFile(f);
                if (out == null) {
                    return false;
                }
                settings.put("object.name", out.getAbsolutePath());
                String command = parseString(recipe);
                boolean ok = execAsynchronously(command);
                if (!ok) {
                    return false;
                }
                out.delete();
            }
        }
        return true;
    }

    public boolean compileLibrary(Library lib) {
        File archive = getCacheFile(lib.getArchiveName());  //getCacheFile("lib" + lib.getName() + ".a");
        File utility = lib.getUtilityFolder();
        PropertyFile props = mergeAllProperties();

        String recipe = props.get("compile.ar");

        settings.put("library", archive.getAbsolutePath());

        long archiveDate = 0;
        if (archive.exists()) {
            archiveDate = archive.lastModified();
        }

        ArrayList<File> fileList = lib.getSourceFiles();

        String origIncs = settings.get("includes");
        settings.put("includes", origIncs + "::" + "-I" + utility.getAbsolutePath());

        int fileCount = fileList.size();

        int count = 0;
        for (File f : fileList) {
            if (f.lastModified() > archiveDate) {
                File out = compileFile(f);
                if (out == null) {
                    return false;
                }
                settings.put("object.name", out.getAbsolutePath());
                String command = parseString(recipe);
                boolean ok = execAsynchronously(command);
                if (!ok) {
                    archive.delete();
                    return false;
                }
                count++;
                lib.setCompiledPercent(count * 100 / fileCount);
                if (editor != null) { 
                    editor.updateLibrariesTree();
                }
                out.delete();
            }
        }
        if (editor != null) { 
            editor.updateOutputTree();
        }
        settings.put("includes", origIncs);
        return true;
    }

    private List<File> convertFiles(File dest, List<File> sources) {
        List<File> objectPaths = new ArrayList<File>();
        PropertyFile props = mergeAllProperties();

        settings.put("build.path", dest.getAbsolutePath());

        for (File file : sources) {
            File objectFile = new File(dest, file.getName() + ".o");
            objectPaths.add(objectFile);
            if (objectFile.exists() && objectFile.lastModified() > file.lastModified()) {
                if (Base.preferences.getBoolean("compiler.verbose")) {
                    message("Skipping " + file.getAbsolutePath() + " as not modified.");
                }
                continue;
            }
            String sp = file.getAbsolutePath();
            String sf = sketchFolder.getAbsolutePath();
            sp = sp.substring(sf.length() + 1);

            String spf = file.getParentFile().getAbsolutePath();
            spf = spf.substring(sf.length() + 1);

            File destObjects = new File(dest, spf);
            destObjects.mkdirs();

            File destFile = new File(destObjects, file.getName());
            Base.copyFile(file, destFile);

            settings.put("source.name", sp);
            settings.put("object.name", objectFile.getAbsolutePath());

            if(!execAsynchronously(parseString(props.get("compile.bin"))))
                return null;
            if (!objectFile.exists()) 
                return null;
        }
        return objectPaths;
    } 

    private List<File> compileFiles(File dest, List<File> sSources, List<File> cSources, List<File> cppSources) {

        List<File> objectPaths = new ArrayList<File>();
        PropertyFile props = mergeAllProperties();

        settings.put("build.path", dest.getAbsolutePath());

        for (File file : sSources) {
            String fileName = file.getName();
            String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
            File objectFile = new File(dest, baseName + ".o");
            objectPaths.add(objectFile);

            settings.put("source.name", file.getAbsolutePath());
            settings.put("object.name", objectFile.getAbsolutePath());

            if (objectFile.exists() && objectFile.lastModified() > file.lastModified()) {
                if (Base.preferences.getBoolean("compiler.verbose")) {
                    message("Skipping " + file.getAbsolutePath() + " as not modified.");
                }
                continue;
            }

            if(!execAsynchronously(parseString(props.get("compile.S"))))
                return null;
            if (!objectFile.exists()) 
                return null;
        }

        for (File file : cSources) {
            String fileName = file.getName();
            String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
            File objectFile = new File(dest, baseName + ".o");
            objectPaths.add(objectFile);

            settings.put("source.name", file.getAbsolutePath());
            settings.put("object.name", objectFile.getAbsolutePath());

            if (objectFile.exists() && objectFile.lastModified() > file.lastModified()) {
                if (Base.preferences.getBoolean("compiler.verbose")) {
                    message("Skipping " + file.getAbsolutePath() + " as not modified.");
                }
                continue;
            }

            if(!execAsynchronously(parseString(props.get("compile.c"))))
                return null;
            if (!objectFile.exists()) 
                return null;
        }

        for (File file : cppSources) {
            String fileName = file.getName();
            String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
            File objectFile = new File(dest, baseName + ".o");
            objectPaths.add(objectFile);

            settings.put("source.name", file.getAbsolutePath());
            settings.put("object.name", objectFile.getAbsolutePath());

            if (objectFile.exists() && objectFile.lastModified() > file.lastModified()) {
                if (Base.preferences.getBoolean("compiler.verbose")) {
                    message("Skipping " + file.getAbsolutePath() + " as not modified.");
                }
                continue;
            }

            if(!execAsynchronously(parseString(props.get("compile.cpp"))))
                return null;
            if (!objectFile.exists()) 
                return null;
        }

        return objectPaths;
    }

    private ArrayList<File> compileSketch() {
        ArrayList<File> sf = new ArrayList<File>();

        PropertyFile props = mergeAllProperties();

        // We can only do this if the core supports binary conversions
        if (props.get("compile.bin") != null) {
            File obj = new File(sketchFolder, "objects");
            if (obj.exists()) {
                File buf = new File(buildFolder, "objects");
                buf.mkdirs();
                List<File> uf = convertFiles(buildFolder, findFilesInFolder(obj, null, true));
                if (uf != null) {
                    sf.addAll(uf);
                }
            }
        }

        ArrayList<File> compiledFiles = (ArrayList<File>) compileFiles(
                buildFolder,
                findFilesInFolder(buildFolder, "S", false),
                findFilesInFolder(buildFolder, "c", false),
                findFilesInFolder(buildFolder, "cpp", false)
        );
        if (compiledFiles != null) {
            sf.addAll(compiledFiles);
        } else {
            return null;
        }
        
        String boardFiles = props.get("build.files");
        if (boardFiles != null) {
            if (!boardFiles.equals("")) {
                ArrayList<File> sFiles = new ArrayList<File>();
                ArrayList<File> cFiles = new ArrayList<File>();
                ArrayList<File> cppFiles = new ArrayList<File>();

                String[] bfs = boardFiles.split("::");
                for (String bf : bfs) {
                    if (bf.endsWith(".c")) {
                        File f = new File(getBoard().getFolder(), bf);
                        if (f.exists()) {
                            cFiles.add(f);
                        }
                    } else if (bf.endsWith(".cpp")) {
                        File f = new File(getBoard().getFolder(), bf);
                        if (f.exists()) {
                            cppFiles.add(f);
                        }
                    } else 
                    if (bf.endsWith(".S")) {
                        File f = new File(getBoard().getFolder(), bf);
                        if (f.exists()) {
                            sFiles.add(f);
                        }
                    } 
                }
                sf.addAll(compileFiles(buildFolder, sFiles, cFiles, cppFiles));
            }
        }

        File suf = new File(sketchFolder, "utility");
        if (suf.exists()) {
            File buf = new File(buildFolder, "utility");
            buf.mkdirs();
            List<File> uf = compileFiles(
                buf,
                findFilesInFolder(suf, "S", true),
                findFilesInFolder(suf, "c", true),
                findFilesInFolder(suf, "cpp", true)
            );
            sf.addAll(uf);
        }

        return sf;
    } 

    static public ArrayList<File> findFilesInFolder(File sketchFolder,
            String extension, boolean recurse) {
        ArrayList<File> files = new ArrayList<File>();

        if (sketchFolder.listFiles() == null)
            return files;

        for (File file : sketchFolder.listFiles()) {
            if (file.getName().startsWith("."))
                continue; // skip hidden files

            if (file.isDirectory()) {
                if (recurse) {
                    files.addAll(findFilesInFolder(file, extension, recurse));
                }
                continue;
            }

            if (extension == null) {
                files.add(file);
                continue;
            }

            if (file.getName().endsWith("." + extension)) {
                files.add(file);
                continue;
            }

        }

        return files;
    }

    static private boolean createFolder(File folder) {
        if (folder.isDirectory())
            return false;
        if (!folder.mkdir())
            return false;
        return true;
    }

    private boolean compileLink(List<File> objectFiles) {
        PropertyFile props = mergeAllProperties();
        HashMap<String, ArrayList<File>> coreLibs = getCoreLibs();
        
        String baseCommandString = props.get("compile.link");
        String commandString = "";
        String objectFileList = "";

        settings.put("libraries.path", getCacheFolder().getAbsolutePath());

        String neverInclude = props.get("neverinclude");
        if (neverInclude == null) {
            neverInclude = "";
        }
        neverInclude.replaceAll(" ", "::");
        String neverIncludes[] = neverInclude.split("::");

        String liboption = props.get("compile.liboption");
        if (liboption == null) {
            liboption = "-l${library}";
        }

        String liblist = "";
        for (String libName : importedLibraries.keySet()) {
            Library lib = importedLibraries.get(libName);
            File aFile = getCacheFile(lib.getArchiveName());
            String headerName = lib.getName() + ".h";
            boolean inc = true;
            for (String ni : neverIncludes) {
                if (ni.equals(headerName)) {
                    inc = false;
                }
            }

            if (aFile.exists() && inc) {
                settings.put("library", lib.getLinkName());
                liblist += "::" + parseString(liboption);
            }
        }

        for (String libName : coreLibs.keySet()) {
            settings.put("library", "Core_" + libName);
            liblist += "::" + parseString(liboption);
        }
        
        settings.put("libraries", liblist);

        for (File file : objectFiles) {
            objectFileList = objectFileList + file.getAbsolutePath() + "::";
        }

        settings.put("build.path", buildFolder.getAbsolutePath());
        settings.put("object.filelist", objectFileList);

        commandString = parseString(baseCommandString);
        return execAsynchronously(commandString);
    }

    private boolean compileEEP() {
        PropertyFile props = mergeAllProperties();
        return execAsynchronously(parseString(props.get("compile.eep")));
    }

    private boolean compileLSS() {
        PropertyFile props = mergeAllProperties();
        return execAsynchronously(parseString(props.get("compile.lss")));
    }

    private boolean compileHEX() {
        PropertyFile props = mergeAllProperties();
        return execAsynchronously(parseString(props.get("compile.hex")));
    }

    public boolean execAsynchronously(String command) {
        PropertyFile props = mergeAllProperties();
        if (command == null) {
            return true;
        }

        String[] commandArray = command.split("::");
        List<String> stringList = new ArrayList<String>();
        for(String string : commandArray) {
            string = string.trim();
            if(string != null && string.length() > 0) {
                stringList.add(string);
            }
        }

        stringList.set(0, stringList.get(0).replace("//", "/"));

        ProcessBuilder process = new ProcessBuilder(stringList);
//        process.redirectOutput(ProcessBuilder.Redirect.PIPE);
//        process.redirectError(ProcessBuilder.Redirect.PIPE);
        if (buildFolder != null) {
            process.directory(buildFolder);
        }
//        Map<String, String> environment = process.environment();
//
//        String pathvar = "PATH";
//        if (Base.isWindows()) {
//            pathvar = "Path";
//        }
//
//        String paths = all.get("path");
//        if (paths != null) {
//            for (String p : paths.split("::")) {
//                String oPath = environment.get(pathvar);
//                if (oPath == null) {
//                    oPath = System.getenv(pathvar);
//                }
//                environment.put(pathvar, oPath + File.pathSeparator + parseString(p));
//            }
//        }
//
//        String env = all.get("environment");
//        if (env != null) {
//            for (String ev : env.split("::")) {
//                String[] bits = ev.split("=");
//                if (bits.length == 2) {
//                    environment.put(bits[0], parseString(bits[1]));
//                }
//            }
//        }

        StringBuilder sb = new StringBuilder();
        for (String component : stringList) {
            sb.append(component);
            sb.append(" ");
        }
        Debug.message("Execute: " + sb.toString());

        if (Base.preferences.getBoolean("compiler.verbose")) {
            message("");
            message(sb.toString());
        }

        Process proc;
        try {
            proc = process.start();
        } catch (Exception e) {
            error(e);
            return false;
        }

        Base.processes.add(proc);

        MessageSiphon in = new MessageSiphon(proc.getInputStream(), this);
        MessageSiphon err = new MessageSiphon(proc.getErrorStream(), this);
        in.setChannel(0);
        err.setChannel(2);
        boolean running = true;
        int result = -1;
        while (running) {
            try {
                if (in.thread != null)
                    in.thread.join();
                if (err.thread != null)
                    err.thread.join();
                result = proc.waitFor();
                running = false;
            } catch (Exception ignored) { }
        }
        Base.processes.remove(proc);
        if (result == 0) {
            return true;
        }
        return false;
    }

    public boolean isUntitled() {
        return isUntitled;
    }


    // Scan all the "common" areas where examples may be found, and see if the 
    // start of the sketch folder's path matches any of them.

    public boolean isInternal() {
        String path = sketchFolder.getAbsolutePath();
        String basePath = Base.getContentFile(".").getAbsolutePath() + File.separator;
        String libsPath = Base.getUserLibrariesFolder().getAbsolutePath() + File.separator;
        String cachePath = getCacheFolder().getAbsolutePath() + File.separator;
        String corePath = getCore().getFolder().getAbsolutePath() + File.separator;
        String boardPath = getBoard().getFolder().getAbsolutePath() + File.separator;

        if (path.startsWith(basePath)) return true;
        if (path.startsWith(libsPath)) return true;
        if (path.startsWith(cachePath)) return true;
        if (path.startsWith(corePath)) return true;
        if (path.startsWith(boardPath)) return true;

        return false;    
    }

    public void cleanBuild() {
        System.gc();
        Base.removeDescendants(buildFolder);
    }

    // Merge all the layers into one single property file
    // class.  This will be the compiler, then the core, then
    // the board, then the pragmas and finally the run-time settings.

    public PropertyFile mergeAllProperties() {
        PropertyFile total = new PropertyFile();

        if (getCompiler() == null) {
            error("==> No compiler selected, skipping.");
        } else {
            total.mergeData(getCompiler().getProperties());
        }

        if (getCore() == null) { 
            error("==> No core");
        } else {
            total.mergeData(getCore().getProperties());
        }

        if (getBoard() == null) {
            error("==> No board");
        } else {
            total.mergeData(getBoard().getProperties());
        }

        if (parameters != null) {
            total.mergeData(parameters);
        }
        if (settings != null) {
            total.mergeData(settings);
        }

        return total;
    }

    public void about() {
        message("Sketch folder: " + sketchFolder.getAbsolutePath());
        message("Selected board: " + getBoard().getName());
        message("Board folder: " + getBoard().getFolder().getAbsolutePath());
    }

    public File getLibrariesFolder() {
        return new File(sketchFolder, "libraries");
    }

    public HashMap<String, Library> getLibraries() {
        return importedLibraries;
    }

    public void programBootloader(String programmer) {
        String uploadCommand;

        File bootloader = getBoard().getBootloader();
        String blName = bootloader.getAbsolutePath();

        PropertyFile props = mergeAllProperties();
        message(Translate.t("Burning Bootloader..."));
        settings.put("filename", blName);

        boolean isJava = true;
        uploadCommand = getBoard().get("upload." + programmer + ".command.java");
        if (uploadCommand == null) {
            uploadCommand = getCore().get("upload." + programmer + ".command.java");
        }
        if (uploadCommand == null) {
            isJava = false;
            uploadCommand = getBoard().get("upload." + programmer + ".command." + Base.getOSFullName());
        }
        if (uploadCommand == null) {
            uploadCommand = getBoard().get("upload." + programmer + ".command." + Base.getOSName());
        }
        if (uploadCommand == null) {
            uploadCommand = getBoard().get("upload." + programmer + ".command");
        }
        if (uploadCommand == null) {
            uploadCommand = getCore().get("upload." + programmer + ".command." + Base.getOSFullName());
        }
        if (uploadCommand == null) {
            uploadCommand = getCore().get("upload." + programmer + ".command." + Base.getOSName());
        }
        if (uploadCommand == null) {
            uploadCommand = getCore().get("upload." + programmer + ".command");
        }

        if (uploadCommand == null) {
            error("No upload command defined for board");
            error(Translate.t("Upload Failed"));
            return;
        }
   
        if (isJava) {
            Plugin uploader;
            uploader = Base.plugins.get(uploadCommand);
            if (uploader == null) {
                error("Upload class " + uploadCommand + " not found.");
                error(Translate.t("Upload Failed"));
                return;
            }
            try {
                if ((uploader.flags() & BasePlugin.LOADER) == 0) {
                    error(uploadCommand + "is not a valid loader plugin.");
                    error(Translate.t("Upload Failed"));
                    return;
                }
                uploader.run();
            } catch (Exception e) {
                error(Translate.t("Upload Failed"));
                error(e.toString());
                return;
            }
            message(Translate.t("Upload Complete"));
            return;
        }

        String[] spl;
        spl = parseString(uploadCommand).split("::");

        String executable = spl[0];
        if (Base.isWindows()) {
            executable = executable + ".exe";
        }

        File exeFile = new File(sketchFolder, executable);
        File tools;
        if (!exeFile.exists()) {
            tools = new File(sketchFolder, "tools");
            exeFile = new File(tools, executable);
        }
        if (!exeFile.exists()) {
            exeFile = new File(getCore().getFolder(), executable);
        }
        if (!exeFile.exists()) {
            tools = new File(getCore().getFolder(), "tools");
            exeFile = new File(tools, executable);
        }
        if (!exeFile.exists()) {
            exeFile = new File(executable);
        }
        if (exeFile.exists()) {
            executable = exeFile.getAbsolutePath();
        }

        spl[0] = executable;

        // Parse each word, doing String replacement as needed, trimming it, and
        // generally getting it ready for executing.

        String commandString = executable;
        for (int i = 1; i < spl.length; i++) {
            String tmp = spl[i];
            tmp = tmp.trim();
            if (tmp.length() > 0) {
                commandString += "::" + tmp;
            }
        }

        boolean dtr = false;
        boolean rts = false;


        String ulu = props.get("upload." + programmer + ".using");
        if (ulu == null) ulu = "serial";

        String doDtr = props.get("upload." + programmer + ".dtr");
        if (doDtr != null) {
            if (doDtr.equals("yes")) {
                dtr = true;
            }
        }
        String doRts = props.get("upload." + programmer + ".rts");
        if (doRts != null) {
            if (doRts.equals("yes")) {
                rts = true;
            }
        }

        if (ulu.equals("serial") || ulu.equals("usbcdc"))
        {
            if (dtr || rts) {
                assertDTRRTS(dtr, rts);
            }
        }
        if (ulu.equals("usbcdc")) {
            try {
                String baud = props.get("upload." + programmer + ".reset.baud");
                if (baud != null) {
                    int b = Integer.parseInt(baud);
                    
                    SerialPort serialPort = Serial.requestPort(getSerialPort(), this, b);
                    if (serialPort == null) {
                        error("Unable to lock serial port");
                        return;
                    }
                    Thread.sleep(1000);
                    Serial.releasePort(serialPort);
                    serialPort = null;
                    System.gc();
                    Thread.sleep(1500);
                }
            } catch (Exception e) {
                error(e);
            }
        }

        boolean res = execAsynchronously(commandString);
        if (ulu.equals("serial") || ulu.equals("usbcdc"))
        {
            if (dtr || rts) {
                assertDTRRTS(false, false);
            }
        }
        if (res) {
            message(Translate.t("Upload Complete"));
        } else {
            error(Translate.t("Upload Failed"));
        }
    }

    /**************************************************************************
     * MESSAGING AND FEEDBACK                                                 *
     *                                                                        *
     * Any output to the user gets fed through these routines.  If there is   *
     * a valid editor then the output goes to the editor's console, otherwise *
     * it goes to stdout / stderr.                                            *
     **************************************************************************/

    public void message(String s) {
        if (!s.endsWith("\n")) {
            s += "\n";
        }
        if (stdoutRedirect != null) {
            try {
                stdoutRedirect.write(s);
            } catch (Exception e) {
            }
            return;
        }

        if (editor != null) {
            editor.message(s);
        } else {
            System.out.println(s);
        }
    }

    public void warning(String s) {
        if (!s.endsWith("\n")) {
            s += "\n";
        }
        if (stdoutRedirect != null) {
            try {
                stdoutRedirect.write(s);
            } catch (Exception e) {
            }
            return;
        }
        if (editor != null) {
            editor.warning(s);
        } else {
            System.out.println(s);
        }
    }

    public void error(String s) {
        if (!s.endsWith("\n")) {
            s += "\n";
        }
        if (stderrRedirect != null) {
            try {
                stderrRedirect.write(s);
            } catch (Exception e) {
            }
            return;
        }
        if (editor != null) {
            editor.error(s);
        } else {
            System.err.println(s);
        }
    }

    public void error(Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        error(sw.toString());
    }

    /**************************************************************************
     * OPTIONS HANDLING                                                       *
     *                                                                        *
     * Any options specified by the Options menu are controlled through these *
     * routines.                                                              *
     **************************************************************************/

    public String getOption(String opt) {
        PropertyFile props = mergeAllProperties();
        PropertyFile opts = Base.preferences.getChildren("board." + selectedBoard.getName() + ".options");
        String optval = opts.get(opt);
        if (optval == null) {
            optval = props.get("options." + opt + ".default");
        }
        return optval;
    }

    public void setOption(String opt, String val) {
        PropertyFile props = mergeAllProperties();
        Base.preferences.set("board." + selectedBoard.getName() + ".options." + opt, val);
        Base.preferences.save();
        if (props.getBoolean("options." + opt + ".purge")) {
            needPurge();
        }
    }

    public HashMap<String, String> getOptionGroups() {
        PropertyFile props = mergeAllProperties();

        String[] options = props.childKeysOf("options");
        HashMap<String, String> out = new HashMap<String, String>();

        for (String opt : options) {
            String optName = props.get("options." + opt + ".name");
            out.put(opt, optName);
        }
        return out;
    }

    public HashMap<String, String> getOptionNames(String group) {
        HashMap<String, String> out = new HashMap<String, String>();
        PropertyFile props = mergeAllProperties();
        PropertyFile opts = props.getChildren("options." + group);

        for (String opt : opts.childKeys()) {
            if (opt.equals("name")) continue;
            if (opt.equals("default")) continue;
            if (opt.equals("purge")) continue;
            String name = opts.get(opt + ".name");
            if (name != null) {
                out.put(opt, name);
            }
        }
        return out;
    }

    public String getFlags(String type) {
        PropertyFile props = mergeAllProperties();
        PropertyFile opts = Base.preferences.getChildren("board." + selectedBoard.getName() + ".options");
        HashMap<String, String> options = getOptionGroups();

        String flags = "";
        for (String opt : options.keySet()) {
            String value = getOption(opt);
            String data = props.get("options." + opt + "." + value + "." + type);
            if (data != null) {
                flags = flags + "::" + data;
            }
        }
        return flags;
    }

    public File getBinariesFolder() {
        return new File(sketchFolder, "objects");
    }

    public boolean libraryIsCompiled(Library l) {
        if (l == null) return false;
        if (l.isHeaderOnly()) {
            return true;
        }
        File arch = new File(getCacheFolder(), l.getArchiveName());
        return arch.exists();
    }

    public void purgeLibrary(Library lib) {
        File arch = new File(getCacheFolder(), lib.getArchiveName());
        arch.delete();
    }

    public void precompileLibrary(Library lib) {
        settings.put("includes", generateIncludes());
        settings.put("filename", sketchName);

        if (doPrePurge) {
            doPrePurge = false;
            Base.removeDir(getCacheFolder());
        }

        settings.put("option.flags", getFlags("flags"));
        settings.put("option.cflags", getFlags("cflags"));
        settings.put("option.cppflags", getFlags("cppflags"));
        settings.put("option.ldflags", getFlags("ldflags"));

        System.err.println("Compiling " + lib + " from " + lib.getFolder().getAbsolutePath() + " to " + lib.getArchiveName());

        compileLibrary(lib);
    }
}
