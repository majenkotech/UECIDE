package org.uecide.gui.swing.laf;

import org.uecide.*;

import javax.swing.UIManager;

public class Office2003LAF extends LookAndFeel {
    public String getName() { return "Office 2003"; }

    public void applyLAF() {
        if (!UECIDE.isWindows()) {
            UECIDE.error("The selected Look and Feel is only compatible with Windows. Select another.");
            return;
        }

        try {
            UIManager.setLookAndFeel("org.fife.plaf.Office2003.Office2003LookAndFeel");
        } catch (Exception e) {
            UECIDE.error(e);
        }
    }

    public boolean isCompatible() { return UECIDE.isWindows(); }

}