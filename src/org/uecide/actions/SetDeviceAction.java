package org.uecide.actions;

import org.uecide.*;
import java.io.File;

public class SetDeviceAction extends Action {

    public SetDeviceAction(Context c) { super(c); }

    public boolean actionPerformed(Object[] args) throws ActionException {
        try {

            if (args.length != 1) {
                throw new SyntaxErrorActionException();
            }

            if (args[0] instanceof CommunicationPort) {
                ctx.setDevice((CommunicationPort)args[0]);
                ctx.action("setPref", "board." + ctx.getBoard().getName() + ".port", ctx.getDevice().toString());
                return true;
            } else if (args[0] instanceof String) {
                String name = (String)args[0];

                for (CommunicationPort dev : Base.communicationPorts) {
                    if (dev.toString().equals(name)) {
                        ctx.setDevice(dev);
                        ctx.action("setPref", "board." + ctx.getBoard().getName() + ".port", ctx.getDevice().toString());
                        return true;
                    }
                }
                if (Base.isPosix()) {
                    File f = new File(name);
                    if (f.exists() && !f.isDirectory()) {
                        SerialCommunicationPort p = new SerialCommunicationPort(f.getAbsolutePath());
                        Base.communicationPorts.add(p);
                        ctx.setDevice(p);
                        ctx.action("setPref", "board." + ctx.getBoard().getName() + ".port", ctx.getDevice().toString());
                        return true;
                    }
                }

                ctx.error("Unknown device: " + (String)args[0]);
                return false;
            }
            throw new BadArgumentActionException();
        } catch (Exception ex) {
            ex.printStackTrace();
            throw new ActionException(ex.getMessage());
        }
    }
}
