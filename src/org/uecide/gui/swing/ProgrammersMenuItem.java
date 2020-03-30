package org.uecide.gui.swing;

import org.uecide.Context;
import org.uecide.Debug;
import org.uecide.Programmer;
import org.uecide.UECIDE;

import java.util.TreeSet;
import java.util.TreeMap;

import java.io.File;

import javax.swing.JRadioButtonMenuItem;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class ProgrammersMenuItem extends JRadioButtonMenuItem implements ActionListener {

    Context ctx;
    Programmer programmer;

    public ProgrammersMenuItem(Context c, Programmer b) {
        super(b.getDescription());
        ctx = c;
        programmer = b;
        if (programmer == ctx.getProgrammer()) {
            setSelected(true);
        } else {
            setSelected(false);
        }
        try {
            setIcon(new CleverIcon(16, programmer.getIcon()));
        } catch (Exception ex) {
            Debug.exception(ex);
        }
        addActionListener(this);
    }

    public void actionPerformed(ActionEvent e) {
        ctx.action("setProgrammer", programmer);
    }
}
