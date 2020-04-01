package org.uecide.actions;

import org.uecide.*;
import java.io.File;

public class BuildAndUploadAction extends Action {

    public BuildAndUploadAction(Context c) { super(c); }

    public String[] getUsage() {
        return new String[] {
            "BuildAndUpload"
        };
    }

    public String getCommand() { return "buildandupload"; }

    public boolean actionPerformed(Object[] args) throws ActionException {
        try {
            boolean r = ctx.getSketch().build();
            if (r == false) return false;

            ctx.triggerEvent("uploadStart");
            String filename = ctx.getSketch().getName();

            Programmer p = ctx.getProgrammer();
            if (p == null) {
                ctx.triggerEvent("uploadFail");
                throw new ActionException("No Programmer Selected");
            }

            r = p.programFile(ctx, filename);
            if (r) {
                ctx.triggerEvent("uploadFinished");
            } else {
                ctx.triggerEvent("uploadFail");
            }
            return r;
        } catch (Exception ex) {
            Debug.exception(ex);
            ctx.triggerEvent("uploadFail");
            throw new ActionException(ex.getMessage());
        }
    }
}