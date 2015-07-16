/* -*-mode:java; c-basic-offset:2; indent-tabs-mode:nil -*- */
/**
* This program will demonstrate remote exec.
* $ CLASSPATH=.:../build javac Exec.java
* $ CLASSPATH=.:../build java Exec
* You will be asked username, hostname, displayname, passwd and command.
* If everything works fine, given command will be invoked
* on the remote side and outputs will be printed out.
*
*/
package org.uecide.builtin;

import org.uecide.*;
import com.jcraft.jsch.*;
import java.awt.*;
import javax.swing.*;
import java.io.*;

public class ssh  implements BuiltinCommand {
    String host;
    String user;

    public boolean main(Context ctx, String[] arg) {
        try {
            JSch jsch = new JSch();

            if(arg.length != 2) {
                ctx.error("Usage: __builtin_ssh user@host command");
                return false;
            }

            host = arg[0];

            user = host.substring(0, host.indexOf('@'));
            host = host.substring(host.indexOf('@') + 1);

            Session session = jsch.getSession(user, host, 22);

            String password = Preferences.get("ssh." + host + "." + user);

            if(password == null) {
                password = Base.session.get("ssh." + host + "." + user);
            }

            if(password == null) {
                password = askPassword();

                if(password == null) {
                    return false;
                }
            }

            session.setPassword(password);
            session.setConfig("StrictHostKeyChecking", "no");

            try {
                session.connect();
            } catch(JSchException e) {
                if(e.getMessage().equals("Auth fail")) {
                    password = null;
                    Preferences.unset("ssh." + host + "." + user);
                    Base.session.unset("ssh." + host + "." + user);
                    ctx.error("Authentication failed");
                    session.disconnect();
                    return false;
                } else {
                    ctx.error(e);
                    return false;
                }
            } catch(Exception e) {
                ctx.error(e);
                return false;
            }

            Base.session.set("ssh." + host + "." + user, password);


            String command = arg[1];

            Channel channel = session.openChannel("exec");
            ((ChannelExec)channel).setCommand(command);

            channel.setInputStream(null);

            InputStream in = channel.getInputStream();
            InputStream err = ((ChannelExec)channel).getErrStream();

            channel.connect();

            byte[] tmp = new byte[1024];

            while(true) {
                while(in.available() > 0) {
                    int i = in.read(tmp, 0, 20);

                    if(i < 0)break;

                    ctx.messageStream(new String(tmp, 0, i));
                }

                while(err.available() > 0) {
                    int i = err.read(tmp, 0, 20);

                    if(i < 0)break;

                    ctx.errorStream(new String(tmp, 0, i));
                }

                if(channel.isClosed()) {
                    if(in.available() > 0) continue;

                    break;
                }

                try {
                    Thread.sleep(1000);
                } catch(Exception ee) {}
            }

            channel.disconnect();
            session.disconnect();
        } catch(Exception e) {
            ctx.error(e);
        }

        return true;
    }

    public String askPassword() {
        JTextField passwordField = (JTextField)new JPasswordField(20);
        JCheckBox save = new JCheckBox("Remember password");
        Object[] ob = {passwordField, save};
        int result = JOptionPane.showConfirmDialog(null, ob, "Enter password for " + user + "@" + host, JOptionPane.OK_CANCEL_OPTION);

        if(result == JOptionPane.CANCEL_OPTION) {
            return null;
        }

        if(save.isSelected()) {
            Preferences.set("ssh." + host + "." + user, passwordField.getText());
        }

        return passwordField.getText();
    }
}
