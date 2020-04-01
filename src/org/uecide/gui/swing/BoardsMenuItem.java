package org.uecide.gui.swing;

import org.uecide.UECIDE;
import org.uecide.Context;
import org.uecide.Board;
import org.uecide.Debug;

import java.util.TreeSet;
import java.util.TreeMap;

import java.io.File;

import javax.swing.JRadioButtonMenuItem;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class BoardsMenuItem extends JRadioButtonMenuItem implements ActionListener {

    Context ctx;
    Board board;

    public BoardsMenuItem(Context c, Board b) {
        super(b.getDescription());
        ctx = c;
        board = b;
        if (board == ctx.getBoard()) {
            setSelected(true);
        } else {
            setSelected(false);
        }
        try {
            setIcon(new CleverIcon(16, board.getIcon()));
        } catch (Exception ex) {
            Debug.exception(ex);
        }
        addActionListener(this);
    }

    public void actionPerformed(ActionEvent e) {
        ctx.action("setBoard", board);
    }
}