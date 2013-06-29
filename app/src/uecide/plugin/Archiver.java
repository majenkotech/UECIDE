/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
  Archiver - plugin tool for archiving sketches
  Part of the Processing project - http://processing.org

  Copyright (c) 2004-06 Ben Fry and Casey Reas

  This program is free software; you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation; either version 2 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software Foundation,
  Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package uecide.plugin;

import uecide.app.*;

import java.awt.FileDialog;
import java.io.*;
import java.net.*;
import java.text.*;
import java.util.*;
import java.util.zip.*;


public class Archiver extends BasePlugin {

  // someday these will be settable
  boolean useDate;
  int digits = 3;

  NumberFormat numberFormat;
  SimpleDateFormat dateFormat;

  public String getMenuTitle() {
    return "Archive Sketch";
  }
  
  
  public void init(Editor editor) {
    this.editor = editor;

    numberFormat = NumberFormat.getInstance();
    numberFormat.setGroupingUsed(false); // no commas
    numberFormat.setMinimumIntegerDigits(digits);

    dateFormat = new SimpleDateFormat("yyMMdd");
  }


  public void run() {
    Sketch sketch = editor.getSketch();
    
    // first save the sketch so that things don't archive strangely
    boolean success = false;
    try {
      success = sketch.save();
    } catch (Exception e) {
      e.printStackTrace();
    }
    if (!success) {
      Base.showWarning("Couldn't archive sketch",
                       "Archiving the sketch has been canceled because\n" +
                       "the sketch couldn't save properly.", null);
      return;
    }

    File location = sketch.getFolder();
    String name = location.getName();
    File parent = new File(location.getParent());

    //System.out.println("loc " + location);
    //System.out.println("par " + parent);

    File newbie = null;
    String namely = null;
    int index = 0;
    do {
      // only use the date if the sketch name isn't the default name
      useDate = !name.startsWith("sketch_");

      if (useDate) {
        String purty = dateFormat.format(new Date());
        String stamp = purty + ((char) ('a' + index));
        namely = name + "-" + stamp;
        newbie = new File(parent, namely + ".zip");

      } else {
        String diggie = numberFormat.format(index + 1);
        namely = name + "-" + diggie;
        newbie = new File(parent, namely + ".zip");
      }
      index++;
    } while (newbie.exists());

    // open up a prompt for where to save this fella
    FileDialog fd =
      new FileDialog(editor, "Archive sketch as:", FileDialog.SAVE);
    fd.setDirectory(parent.getAbsolutePath());
    fd.setFile(newbie.getName());
    fd.setVisible(true);

    String directory = fd.getDirectory();
    String filename = fd.getFile();

    // only write the file if not canceled
    if (filename != null) {
      newbie = new File(directory, filename);

      try {
        //System.out.println(newbie);
        FileOutputStream zipOutputFile = new FileOutputStream(newbie);
        ZipOutputStream zos = new ZipOutputStream(zipOutputFile);

        // recursively fill the zip file
        buildZip(location, name, zos);

        // close up the jar file
        zos.flush();
        zos.close();

        editor.statusNotice("Created archive " + newbie.getName() + ".");

      } catch (IOException e) {
        e.printStackTrace();
      }
    } else {
      editor.statusNotice("Archive sketch canceled.");
    }
  }


  public void buildZip(File dir, String sofar,
                       ZipOutputStream zos) throws IOException {
    String files[] = dir.list();
    for (int i = 0; i < files.length; i++) {
      if (files[i].equals(".") ||
          files[i].equals("..")) continue;

      File sub = new File(dir, files[i]);
      String nowfar = (sofar == null) ?
        files[i] : (sofar + "/" + files[i]);

      if (sub.isDirectory()) {
        // directories are empty entries and have / at the end
        ZipEntry entry = new ZipEntry(nowfar + "/");
        //System.out.println(entry);
        zos.putNextEntry(entry);
        zos.closeEntry();
        buildZip(sub, nowfar, zos);

      } else {
        ZipEntry entry = new ZipEntry(nowfar);
        entry.setTime(sub.lastModified());
        zos.putNextEntry(entry);
        zos.write(Base.loadBytesRaw(sub));
        zos.closeEntry();
      }
    }
  }
}