package org.uecide.gui.swing;

import org.uecide.Context;
import org.uecide.Debug;
import org.uecide.FileType;
import org.uecide.SketchFile;

import javax.swing.ImageIcon;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JLabel;
import javax.swing.JTree;

import java.io.IOException;

import java.awt.Font;
import java.awt.Component;

public class SketchHeaderFilesNode extends SketchTreeNodeBase {
    public SketchHeaderFilesNode(Context c, SketchTreeModel m) {
        super(c, m, "Headers");
        updateChildren();
    }

    public boolean updateChildren() {
        removeAllChildren();
        for (SketchFile f : ctx.getSketch().getSketchFiles().values()) {
            if (f.getGroup() == FileType.GROUP_HEADER) {
                SketchSourceFileNode sfn = new SketchSourceFileNode(ctx, model, f);
                add(sfn);
            }
        }
        model.reload(this);
        return true;
    }

    public ImageIcon getIcon(JTree tree) throws IOException {
        if (tree.isExpanded(getTreePath())) {
            return IconManager.getIcon(16, "tree.folder-open");
        } else {
            return IconManager.getIcon(16, "tree.folder-closed");
        }
    }

    public JPopupMenu getPopupMenu() {
        JPopupMenu menu = new JPopupMenu();
        return menu;
    }

    public void performDoubleClick() {
    }

    public Component getRenderComponent(JLabel original, JTree tree) {
        try {
            original.setIcon(getIcon(tree));
        } catch (Exception ex) {
            Debug.exception(ex);
        }

        return original;
    }

}
