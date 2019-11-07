/*
 * Copyright (c) 2015, Majenko Technologies
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

package org.uecide;

import java.io.*;
import java.lang.*;
import java.util.*;

import javax.script.*;

import java.util.regex.*;

import org.uecide.builtin.*;
import org.uecide.varcmd.*;
import org.uecide.actions.*;
import org.uecide.gui.*;



// Simple context container to hold the current context while running scripts and things.
// It is here that all execution, messaging, string parsing, etc should happen.

public class Context {
    public Board board = null;
    public Core core = null;
    public Compiler compiler = null;
    public Programmer programmer = null;
    public Sketch sketch = null;
    public CommunicationPort port = null;
    public Gui gui = null;

    public ContextListener listener = null;

    public StringBuilder buffer = null;

    boolean bufferError = false;

    public PropertyFile settings = null;
    public PropertyFile sketchSettings = null;

    public Process runningProcess = null;

    public PropertyFile savedSettings = null;

    public DataStreamParser parser = null;

    public boolean silence = false;

    public HashMap<String, ArrayList<ContextEventListener>> contextEventListeners = new HashMap<String, ArrayList<ContextEventListener>>();

    public HashMap<String, Thread> threads = new HashMap<String, Thread>();

    // Make a new empty context.

    public Context(Context src) {
        board = src.board;
        core = src.core;
        compiler = src.compiler;
        programmer = src.programmer;
        sketch = src.sketch;
        port = src.port;
        gui = src.gui;
        listener = src.listener;
        buffer = src.buffer;
        bufferError = src.bufferError;
        runningProcess = src.runningProcess;
        parser = src.parser;
        silence = src.silence;

        settings = new PropertyFile(src.settings);
        sketchSettings = new PropertyFile(src.sketchSettings);
        savedSettings = new PropertyFile(src.savedSettings);
    }

    public Context() {
        settings = new PropertyFile();
        sketchSettings = new PropertyFile();
        updateSystem();
    }

    class ThreadRet extends Thread {
        public Object retval;
    }

    // At least one of these should be called to configure the context:

    public void setProgrammer(Programmer p) { programmer = p; triggerEvent("setProgrammer"); updateSystem(); }
    public void setBoard(Board b) { board = b; triggerEvent("setBoard"); updateSystem(); }
    public void setCore(Core c) { core = c; triggerEvent("setCore"); updateSystem(); }
    public void setCompiler(Compiler c) { compiler = c; triggerEvent("setCompiler"); updateSystem(); }
    public void setSketch(Sketch s) { sketch = s; triggerEvent("setSketch"); updateSystem(); }
    public void setGui(Gui g) { gui = g; triggerEvent("setGui"); updateSystem(); }
    public void setDevice(CommunicationPort p) { 
        port = p; 
        if (port != null) {
            set("port", port.getProgrammingPort());
            set("port.base", port.getBaseName());
            set("ip", port.getProgrammingAddress());
        }
        triggerEvent("setDevice");
    }

    public synchronized void updateSystem() {
    }

    public void triggerEvent(String event) {
        Debug.message("Event triggered: " + event);
        if (contextEventListeners.get(event) == null) return;
        for (ContextEventListener target : contextEventListeners.get(event)) {
            target.contextEventTriggered(event, this);
        }
    }

    public void listenForEvent(String event, ContextEventListener target) {
        if (contextEventListeners.get(event) == null) {
            contextEventListeners.put(event, new ArrayList<ContextEventListener>());
        }

        ArrayList<ContextEventListener> listeners = contextEventListeners.get(event);
        listeners.add(target);
    }

    // Getters for all the above.

    public Programmer getProgrammer() { return programmer; }
    public Board getBoard() { return board; }
    public Core getCore() { return core; }
    public Compiler getCompiler() { return compiler; }
    public Sketch getSketch() { return sketch; }
    public Gui getGui() { return gui; }
    public CommunicationPort getDevice() { return port; }


    // Settings can be manipulated with these:

    public void clearSettings() {
        settings = new PropertyFile();
    }

    public void set(String k, String v) {
        settings.set(k, v);
    }

    public String get(String k) {
        return settings.get(k);
    }

    public void mergeSettings(PropertyFile pf) {
        settings.mergeData(pf);
    }

    // Utility function to merge all the property files together in order.

    public PropertyFile getMerged() {
        PropertyFile pf = new PropertyFile();
        if (programmer != null) { pf.mergeData(programmer.getProperties()); }
        if (compiler != null) { pf.mergeData(compiler.getProperties()); }
        if (core != null) { pf.mergeData(core.getProperties()); }
        if (board != null) { pf.mergeData(board.getProperties()); }
        pf.mergeData(sketchSettings);
        pf.mergeData(settings);
        return pf;
    }

    public String getMerged(String k) {
        PropertyFile pf = new PropertyFile();
        if (programmer != null) { pf.mergeData(programmer.getProperties()); }
        if (compiler != null) { pf.mergeData(compiler.getProperties()); }
        if (core != null) { pf.mergeData(core.getProperties()); }
        if (board != null) { pf.mergeData(board.getProperties()); }
        pf.mergeData(sketchSettings);
        pf.mergeData(settings);
        return pf.get(k);
    }

    // Find a resource by its URI.  A URI is not a normal Java URI but a UECIDE
    // specific one.  It may be any one of:
    //
    // res:/path/to/resource - get a resource as a string
    // file:/path/to/file - get a file from disk as a string
    // compiler:name.of.file - Get an embedded file from the compiler
    // core:name.of.file - Get an embedded file from the core
    // board:name.of.file - Get an embedded file from the board
    // merged:name.of.file - Get am embedded file from the merged properties

    public String getResource(String uri) throws IOException {
        if (uri.startsWith("res:")) {
            return Utils.getResourceAsString(uri.substring(4));
        }
        if (uri.startsWith("file:")) {
            return Utils.getFileAsString(new File(uri.substring(5)));
        }
        if (uri.startsWith("compiler:")) {
            return compiler.getEmbedded(uri.substring(9));
        }
        if (uri.startsWith("core:")) {
            return core.getEmbedded(uri.substring(5));
        }
        if (uri.startsWith("board:")) {
            return board.getEmbedded(uri.substring(6));
        }
        if (uri.startsWith("programmer:")) {
            return programmer.getEmbedded(uri.substring(6));
        }
        if (uri.startsWith("sketch:")) {
            return sketchSettings.getEmbedded(uri.substring(7));
        }
        if (uri.startsWith("merged:")) {
            PropertyFile pf = getMerged();
            return pf.getEmbedded(uri.substring(7));
        }
        return null;
    }

    // Reporting and messaging functions.

    public void error(Throwable e) {
        gui.error(e);
    }

    public void error(String e) {
        if (listener != null) {
            listener.contextError(e);
        } else {
            gui.error(e);
        }
    }

    public void warning(String e) {
        if (listener != null) {
            listener.contextWarning(e);
        } else {
            gui.warning(e);
        }
    }
        
    public void message(String e) {
        if (listener != null) {
            listener.contextMessage(e);
        } else {
            gui.message(e);
        }
    }

    public void parsedMessage(String e) {
        if (sketch != null) {
            sketch.parsedMessage(e);
            return;
        }
        printParsed(e);
    }


    public void link(String e) {
        if (!e.endsWith("\n")) { e += "\n"; }
        if (sketch != null) {
            sketch.link(e);
            return;
        }
        System.out.print(e);
    }

    public void command(String e) {
        if ((Preferences.getBoolean("compiler.verbose_compile")) || (Base.cli.isSet("verbose"))) {
            gui.command(e);
        }
    }
        
    public void bullet(String e) {
        gui.bullet(e);
    }
        
    public void bullet2(String e) {
        gui.bullet2(e);
    }

    public void bullet3(String e) {
        gui.bullet3(e);
    }

    public void heading(String e) {
        gui.heading(e);
    }
        
    public void rawMessageStream(String e) {
        if (sketch != null) {
            sketch.outputMessageStream(e);
            return;
        }
        System.err.print(e);
    }

    public void rawErrorStream(String e) {
        if (sketch != null) {
            sketch.outputErrorStream(e);
            return;
        }
        System.err.print(e);
    }

    public void errorStream(String e) {
        if  (listener != null) {
            listener.contextError(e);
        } else {
            if (sketch != null) {
                sketch.errorStream(e);
                return;
            }
            System.err.print(e);
        }
    }

    public void warningStream(String e) {
        if  (listener != null) {
            listener.contextWarning(e);
        } else {
            if (sketch != null) {
                sketch.warningStream(e);
                return;
            }
            System.out.print(e);
        }
    }
        
    public void messageStream(String e) {
        if  (listener != null) {
            listener.contextMessage(e);
        } else {
            if (sketch != null) {
                sketch.messageStream(e);
                return;
            }
            System.out.print(e);
        }
    }
        
    public void printParsed(String message) {

        Pattern pat = Pattern.compile("\\{\\\\(\\w+)\\s*(.*)\\}");

        int openBracketLocation = message.indexOf("{\\");

        // No open sequence means there's no parsing to do - just
        // append the text as BODY and leave it at that.
        if (openBracketLocation == -1) {
            System.out.print(message);
            return;
        }

        while (openBracketLocation >= 0) {
            String leftChunk = message.substring(0, openBracketLocation);
            String rightChunk = message.substring(openBracketLocation);
            int closeBracketLocation = rightChunk.indexOf("}");
            if (closeBracketLocation == -1) {
                // Oops - something went wrong! No close bracket!
                System.err.println("Badly formatted message: " + message);
                return;
            }
            String block = rightChunk.substring(0, closeBracketLocation + 1);
            String remainder = rightChunk.substring(closeBracketLocation + 1);

            if (!leftChunk.equals("")) {
                System.out.print(leftChunk);
            }

            Matcher m = pat.matcher(block);
            if (m.find()) {
                String type = m.group(1);
                String text = m.group(2);
                if (type.equals("body")) {
                    System.out.print(text);
                } else if (type.equals("warning")) {
                    System.out.print("[36m" + text + "[0m");
                } else if (type.equals("error")) {
                    System.out.print("[31m" + text + "[0m");
                } else if (type.equals("command")) {
                    System.out.print(text);
                } else if (type.equals("heading")) {
                    System.out.print(text);
                } else if (type.equals("bullet")) {
                    System.out.print(" * " + text);
                } else if (type.equals("bullet2")) {
                    System.out.print("   * " + text);
                } else if (type.equals("link")) {
                    System.out.print(text);
                }
            }

            message = remainder;
            openBracketLocation = message.indexOf("{\\");
        }
        if (!message.equals("")) {
            System.out.print(message);
        }
    }



    // Command and script execution

    // Execute a key as a script in whatever way is needed.

    public Object executeKey(String key) {
        return executeKey(key, false);
    }

    public Object executeKey(String key, boolean silent) {
        PropertyFile props = getMerged();
    
        // If there is a platform specific version of the key then we should switch to that instead.
        key = props.getPlatformSpecificKey(key);

        // If the key has a sub-key of .0 then run it as a UECIDE Script
        if (props.get(key + ".0") != null) {
            return executeUScript(key, silent);
        }

        // Otherwise try and run it as a command (either built in or system).
        if (props.get(key) != null) {
            return executeCommand(parseString(props.get(key)), parseString(props.get(key + ".environment")), silent);
        }

        return false;
    }

    public Object executeCommand(String command, String env) {
        return executeCommand(command, env, false);
    }

    public Object executeCommand(String command, String env, boolean silent) {
        if(command.startsWith("__builtin_")) {
            return runBuiltinCommand(command, silent);
        } else {
            return runSystemCommand(command, env, silent);
        }
    }


    public Object executeUScript(String key) {
        return executeUScript(key, false);
    }

    public Object executeUScript(String key, boolean silent) {
        PropertyFile props = getMerged();
        PropertyFile script = props.getChildren(key);
        int lineno = 0;

        Object res = false;

        while(script.keyExists(Integer.toString(lineno))) {
            String lk = String.format("%s.%d", key, lineno);
            String linekey = props.keyForOS(lk);
            String ld = props.get(linekey);

            ld = ld.trim();

            if(ld.startsWith("goto::")) {
                ld = parseString(ld);
                String num = ld.substring(6);

                try {
                    lineno = Integer.parseInt(num);
                    continue;
                } catch(Exception e) {
                    error(Base.i18n.string("err.syntax", key, lineno));
                    error(ld);
                    if (script.keyExists("fail")) {
                        String failKey = String.format("%s.fail", key);
                        executeKey(failKey, silent);
                    }
                    return false;
                }
            }

            if(ld.startsWith("set::")) {
                ld = parseString(ld);
                String param = ld.substring(5);
                int epos = param.indexOf("=");

                if(epos == -1) {
                    error(Base.i18n.string("err.syntax", key, lineno));
                    error(ld);
                    if (script.keyExists("fail")) {
                        String failKey = String.format("%s.fail", key);
                        res = executeKey(failKey, silent);
                    }
                    return false;
                }

                String kk = param.substring(0, epos);
                String vv = param.substring(epos + 1);
                settings.set(kk, vv);
                lineno++;
                continue;
            }

            if(ld.equals("fail")) {
                if (script.keyExists("fail")) {
                    String failKey = String.format("%s.fail", key);
                    res = executeKey(failKey, silent);
                }
                return false;
            }

            if(ld.equals("end")) {
                if (script.keyExists("end")) {
                    String endKey = String.format("%s.end", key);
                    res = executeKey(endKey, silent);
                }
                return res;
            }

            res = executeKey(lk, silent);

            if (res instanceof Boolean) {
                if((Boolean)res == false) {
                    if (script.keyExists("fail")) {
                        String failKey = String.format("%s.fail", key);
                        res = executeKey(failKey, silent);
                    }
                    return false;
                }
            }

            lineno++;
        }
        if (script.keyExists("end")) {
            String endKey = String.format("%s.end", key);
            res = executeKey(endKey, silent);
        }

        return res;
    }



    public String parseString(String in) {
        PropertyFile tokens = getMerged();
        
        int iStart;
        int iEnd;
        int iTest;
        String out;
        String start;
        String end;
        String mid;

        if(in == null) {
            return null;
        }

        out = in;

        iStart = out.indexOf("${");

        if(iStart == -1) {
            return out;
        }

        iEnd = out.indexOf("}", iStart);
        iTest = out.indexOf("${", iStart + 1);

        while((iTest > -1) && (iTest < iEnd)) {
            iStart = iTest;
            iTest = out.indexOf("${", iStart + 1);
        }

        while(iStart != -1) {
            start = out.substring(0, iStart);
            end = out.substring(iEnd + 1);
            mid = out.substring(iStart + 2, iEnd);

            // Compatability hack for old format roots
            if (mid.equals("board.root")) { mid = "board:root"; }
            if (mid.equals("core.root")) { mid = "core:root"; }
            if (mid.equals("compiler.root")) { mid = "compiler:root"; }

            if(mid.indexOf(":") > -1) {
                String command = mid.substring(0, mid.indexOf(":"));
                String param = mid.substring(mid.indexOf(":") + 1);

                mid = VariableCommand.run(this, command, param);
            } else {
                String tmid = tokens.get(mid);

                if(tmid == null) {
                    tmid = "";
                }

                mid = tmid;
            }


            if(mid != null) {
                out = start + mid + end;
            } else {
                out = start + end;
            }

            iStart = out.indexOf("${");
            iEnd = out.indexOf("}", iStart);
            iTest = out.indexOf("${", iStart + 1);

            while((iTest > -1) && (iTest < iEnd)) {
                iStart = iTest;
                iTest = out.indexOf("${", iStart + 1);
            }
        }

        // This shouldn't be needed as the methodology should always find any tokens put in
        // by other token replacements.  But just in case, eh?
        if(out != in) {
            out = parseString(out);
        }

        return out;
    }

    public Object runBuiltinCommand(String commandline) {
        return runBuiltinCommand(commandline, false);
    }

    public Object runBuiltinCommand(String commandline, boolean silent) {
        try {
            String[] split = commandline.split("::");
            int argc = split.length - 1;

            String cmdName = split[0];

            String[] arg = new String[argc];

            for(int i = 0; i < argc; i++) {
                arg[i] = split[i + 1];
            }

            if(!cmdName.startsWith("__builtin_")) {
                return false;
            }

            cmdName = cmdName.substring(10);
            if (Preferences.getBoolean("compiler.verbose_compile") && !silence) {

                StringBuilder args = new StringBuilder();

                args.append(cmdName);

                for (String s : arg) {
                    args.append(" ");
                    args.append(s);
                }
                if (!silent) command(args.toString());
            }

            return BuiltinCommand.run(this, cmdName, arg);

        } catch(Exception e) {
            Base.error(e);
        }

        return false;
    }



    public Object runSystemCommand(String command, String env) {
        return runSystemCommand(command, env, false);
    }

    public Object runSystemCommand(String command, String env, boolean silent) {
        PropertyFile props = getMerged();

        Object res;

        if(command == null) {
            return true;
        }

        String[] commandArray = command.split("::");
        ArrayList<String> stringList = new ArrayList<String>();

        for(String string : commandArray) {
            string = string.trim();

            if(string != null && string.length() > 0) {
                stringList.add(string);
            }
        }

        stringList.set(0, stringList.get(0).replace("//", "/"));

        ProcessBuilder process = new ProcessBuilder(stringList);
        runningProcess = null;

        if (env != null) {
            Map<String, String> environment = process.environment();
            for(String ev : env.split("::")) {
                String[] bits = ev.split("=");

                if(bits.length == 2) {
                    environment.put(bits[0], parseString(bits[1]));
                }
            }
        }

        if (props.get("build.path") != null) {
            process.directory(props.getFile("build.path"));
        }

        StringBuilder sb = new StringBuilder();

        for(String component : stringList) {
            sb.append(component);
            sb.append(" ");
        }

        Debug.message("Execute: " + sb.toString());

        if (Preferences.getBoolean("compiler.verbose_compile") && !silence) {
            if (!silent) command(sb.toString());
        }

        try {
            runningProcess = process.start();
        } catch(Exception e) {
            error(Base.i18n.string("err.process"));
            error(e);
            return false;
        }

        Base.processes.add(runningProcess);

        InputStream in = runningProcess.getInputStream();
        InputStream err = runningProcess.getErrorStream();
        boolean running = true;
        int result = -1;

        byte[] tmp = new byte[1];


        String outline = "";
        String errline = "";

        while(isProcessRunning(runningProcess)) {
            try {
                while(in.available() > 0) {
                    int i = in.read(tmp, 0, 1);

                    if(i < 0)break;
                    
                    String inch = new String(tmp, 0, i);
                    if (buffer == null) rawMessageStream(inch);
                    if (parser != null) {
                        if (((inch.charAt(0) >= ' ') && (inch.charAt(0) <= (char)127)) || (inch.charAt(0) == '\n')) {
                            outline += inch;
                        }
                        if (inch.equals("\n")) {
//                            rawMessageStream(outline);
                            outline = parser.parseStreamMessage(this, outline);
                            outline = parser.parseStreamError(this, outline);
                            if (buffer != null) {
                                buffer.append(inch);
                            } else {                        
                                messageStream(inch);
                            }
                            outline = "";
                        }
                    } else {
                        if (buffer != null) {
                            buffer.append(inch);
                        } else {                        
                            messageStream(inch);
                        }
                    }
                }

                while(err.available() > 0) {
                    int i = err.read(tmp, 0, 1);

                    if(i < 0)break;
                    
                    String inch = new String(tmp, 0, i);
                    if (buffer == null) rawErrorStream(inch);
                    if (parser != null) {
                        if (((inch.charAt(0) >= ' ') && (inch.charAt(0) <= (char)127)) || (inch.charAt(0) == '\n')) {
                            errline += inch;
                        }
                        if (inch.equals("\n")) {
//                            rawErrorStream(errline);
                            errline = parser.parseStreamMessage(this, errline);
                            errline = parser.parseStreamError(this, errline);
                            if (buffer != null) {
                                buffer.append(inch);
                            } else {                        
                                errorStream(inch);
                            }
                            errline = "";
                        }
                    } else {
                        if (buffer != null) {
                            buffer.append(inch);
                        } else {                        
                            errorStream(inch);
                        }
                    }
                }

                Thread.sleep(1);

            } catch (InterruptedException ex) {
                runningProcess.destroyForcibly();
                Base.processes.remove(runningProcess);
                error("Aborted");
                return false;

            } catch(Exception ignored) {
                error(ignored);
            }
        }

        try {
            while(in.available() > 0) {
                int i = in.read(tmp, 0, 1);

                if(i < 0)break;
                
                String inch = new String(tmp, 0, i);
                if (((inch.charAt(0) >= ' ') && (inch.charAt(0) <= (char)127)) || (inch.charAt(0) == '\n')) {
                    outline += inch;
                }

                if (buffer == null) rawMessageStream(inch);
                if (inch.equals("\n")) {
                    if (parser != null) {
//                        rawMessageStream(outline);
                        outline = parser.parseStreamMessage(this, outline);
                        outline = parser.parseStreamError(this, outline);
                    }
                    if (buffer != null) {
                        buffer.append(outline);
                    } else {                        
                        messageStream(outline);
                    }
                    outline = "";
                }
            }

            if (!outline.equals("")) {
                if (parser != null) {
//                    rawMessageStream(outline);
                    outline = parser.parseStreamMessage(this, outline);
                    outline = parser.parseStreamError(this, outline);
                }
                if (buffer != null) {
                    buffer.append(outline);
                } else {                        
                    messageStream(outline);
                }
            }

            while(err.available() > 0) {
                int i = err.read(tmp, 0, 1);

                if(i < 0)break;
                
                String inch = new String(tmp, 0, i);
                if (((inch.charAt(0) >= ' ') && (inch.charAt(0) <= (char)127)) || (inch.charAt(0) == '\n')) {
                    errline += inch;
                }

                if (buffer == null) rawErrorStream(inch);

                if (inch.equals("\n")) {
                    if (parser != null) {
//                        rawErrorStream(errline);
                        errline = parser.parseStreamMessage(this, errline);
                        errline = parser.parseStreamError(this, errline);
                    }
                    if (bufferError) {
                        if (buffer != null) {
                            buffer.append(errline);
                        } else {                        
                            errorStream(errline);
                        }
                    } else {
                        errorStream(errline);
                    }
                    errline = "";
                }
            }

            if (!errline.equals("")) {
                if (parser != null) {
//                    rawErrorStream(errline);
                    errline = parser.parseStreamMessage(this, errline);
                    errline = parser.parseStreamError(this, errline);
                }
                if (bufferError) {
                    if (buffer != null) {
                        buffer.append(errline);
                    } else {                        
                        errorStream(errline);
                    }
                } else {
                    errorStream(errline);
                }
            }

        } catch(Exception ignored) {
            String igm = ignored.getMessage();
            if (igm != null) {
                if (igm.equals("Stream closed")) {
                    error(Base.i18n.string("misc.cancelled"));
                } else {
                    error(ignored);
                }
            }
        }

        if (runningProcess != null) {
            result = runningProcess.exitValue();
        }

        Base.processes.remove(runningProcess);

        if(result == 0) {
            return true;
        }

        return false;
    }

    public static boolean isProcessRunning(Process process)
    {
        if (process == null) {
            return false;
        }
        try
        {
            process.exitValue();
            return false;
        }
        catch(IllegalThreadStateException e)
        {
            return true;
        }
    }

    public void killRunningProcess() {
        if(runningProcess != null) {
//            runningProcess.destroy();
//            Base.processes.remove(runningProcess);
        }
    }

    public void startBuffer() {
        startBuffer(false);
    }

    public void startBuffer(boolean be) {
        bufferError = be;
        buffer = new StringBuilder();
    }

    public String endBuffer() {
        String b = "";
        if (buffer != null) {
            b = buffer.toString();
        }
        buffer = null;
        return b;
    }

    public void addContextListener(ContextListener l) {
        listener = l;
    }

    public void removeContextListener() {
        listener = null;
    }

    public void debugDump() {
        PropertyFile pf = getMerged();
        pf.debugDump();
    }

    public void snapshot() {
        savedSettings = settings;
        settings = new PropertyFile();
    }

    public void rollback() {
        settings = savedSettings;
    }

    public void addDataStreamParser(DataStreamParser p) {
        parser = p;
    }

    public void removeDataStreamParser() {
        parser = null;
    }

    public void loadSketchSettings(File pf) {
        if (pf.exists()) {
            sketchSettings = new PropertyFile(pf);
        } else {
            sketchSettings = new PropertyFile();
        }
    }

    public void saveSketchSettings() {
        if (sketch == null) {
            return;
        }
        File f = new File(sketch.getFolder(), "sketch.cfg");
        sketchSettings.save(f);
    }

    public PropertyFile getSketchSettings() {
        return sketchSettings;
    }

    public int getParsedInteger(String k) {
        return getParsedInteger(k, 0);
    }

    public int getParsedInteger(String k, int d) {
        PropertyFile props = getMerged();
        String v = props.get(k);
        if (v == null) return d;
        if (v.equals("")) return d;
        v = parseString(v);
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
        }
        return d;
    }



    public boolean action(String name, Object... args) {
        return Action.run(this, name, args);
    }

    public Thread actionThread(String name, Object... args) {
        Thread t = new ActionThread(this, name, args);
        t.start();
        return t;
    }

    public boolean killThread(String name) {
        Thread t = threads.get(name.toLowerCase());
        if (t == null) return false;
        t.interrupt();
        try {
            t.join();
        } catch (Exception ex) {
            error(ex);
        }
        return true;
    }

    public void runInitScripts() {
        for (Board b : Base.boards.values()) {
            if (b.get("init.script.0") != null) {
                Context ctx = new Context(this);
                ctx.setBoard(b);
                ctx.executeKey("init.script");
            }
        }
        for (Core c : Base.cores.values()) {
            if (c.get("init.script.0") != null) {
                Context ctx = new Context(this);
                ctx.setCore(c);
                ctx.executeKey("init.script");
            }
        }
        for (Compiler c : Base.compilers.values()) {
            if (c.get("init.script.0") != null) {
                Context ctx = new Context(this);
                ctx.setCompiler(c);
                ctx.executeKey("init.script");
            }
        }
        for (Programmer c : Base.programmers.values()) {
            if (c.get("init.script.0") != null) {
                Context ctx = new Context(this);
                ctx.setProgrammer(c);
                ctx.executeKey("init.script");
            }
        }
    }



}
