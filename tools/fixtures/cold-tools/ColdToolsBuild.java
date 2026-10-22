package com.deepseekharness.app.runtime;

import android.system.Os;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.ZipFile;
import org.json.JSONObject;

/** 工具资产的独立 ARM64 制备根；只在 shell 自有目录运行，不访问应用私有数据。 */
public final class ColdToolsBuild {
  private static final JSONObject nodes = new JSONObject();

  public static void main(String[] args) throws Exception {
    File base = new File(args[0]).getCanonicalFile();
    if (!base.getPath().matches("/data/local/tmp/dsha-cold-tools-[a-f0-9]{32}")) throw new IllegalArgumentException("OWNED_BUILD_ROOT");
    File root = new File(base, "rootfs");
    if (args[1].equals("extract")) {
      if (!root.mkdir()) throw new IllegalStateException("ROOT_ALREADY_EXISTS");
      try (ZipFile apk = new ZipFile(new File(base, "source.apk"))) {
        for (String name : new String[]{"offline-rootfs.bin", "glibc-python.bin", "python-support.bin"}) {
          try (var input = apk.getInputStream(apk.getEntry("assets/" + name))) {
            TarGzipExtractor.extractAuto(input, root, 0);
          }
        }
        File slot = new File(root,"root/.dsha-bundled-tools");
        try (var input = apk.getInputStream(apk.getEntry("assets/ubuntu-tools.bin"))) {
          TarGzipExtractor.extractAuto(input,slot,0);
        }
      }
      snapshot(root,root);
      write(new File(base,"before.json"),nodes.toString());
      System.out.println("DSHA_TOOLS_BASE_READY");
    } else if (args[1].equals("export")) {
      JSONObject selected = new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(base,"selection.json").toPath()),"UTF-8"));
      try (var output = new java.util.zip.ZipOutputStream(new FileOutputStream(new File(base,"configured-files.zip")))) {
        var names=selected.keys(); byte[] buffer=new byte[65536];
        while(names.hasNext()) {
          String name=names.next(); if(name.startsWith("/")||name.contains(".."))throw new IllegalArgumentException("EXPORT_PATH");
          JSONObject row=selected.getJSONObject(name);if(!row.getString("type").equals("FILE"))continue;
          output.putNextEntry(new java.util.zip.ZipEntry(name));
          try(var input=new FileInputStream(new File(root,name))){int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);}
          output.closeEntry();
        }
      }
      System.out.println("DSHA_CONFIGURED_FILES_EXPORTED");
    } else if (args[1].equals("snapshot") || args[1].equals("baseline")) {
      snapshot(root,root);
      write(new File(base,args[1].equals("baseline")?"before.json":"after.json"),nodes.toString());
      System.out.println("DSHA_TOOLS_CONFIGURED_SNAPSHOT_READY");
    } else throw new IllegalArgumentException("BUILD_PHASE");
  }

  private static void write(File path,String text) throws Exception {
    try (var output = new FileOutputStream(path)) { output.write(text.getBytes("UTF-8")); output.getFD().sync(); }
  }

  private static void snapshot(File root,File file) throws Exception {
    String relative=file.equals(root)?"":file.getPath().substring(root.getPath().length()+1);
    if (relative.equals("proc")||relative.startsWith("proc/")||relative.equals("dev")||relative.startsWith("dev/")||relative.equals("sys")||relative.startsWith("sys/")||relative.equals("tmp")||relative.startsWith("tmp/")||relative.equals("var/tmp")||relative.startsWith("var/tmp/")||relative.contains(".dsha-bundled-tools")||relative.equals(".l2s")||relative.startsWith(".l2s/")) return;
    var stat=Os.lstat(file.getPath());
    if(android.system.OsConstants.S_ISLNK(stat.st_mode) && file.getCanonicalPath().startsWith(root.getPath()+"/.l2s/"))stat=Os.stat(file.getPath());
    JSONObject node=new JSONObject();node.put("mode",stat.st_mode&0777);node.put("size",stat.st_size);
    if (android.system.OsConstants.S_ISLNK(stat.st_mode)) {node.put("type","LINK");node.put("target",Os.readlink(file.getPath()));}
    else if (android.system.OsConstants.S_ISDIR(stat.st_mode)) {
      node.put("type","DIRECTORY");nodes.put(relative,node);
      String[] children=file.list();if(children==null)throw new IllegalStateException("UNREADABLE:"+relative);
      Arrays.sort(children);for(String child:children)snapshot(root,new File(file,child));return;
    } else if(android.system.OsConstants.S_ISREG(stat.st_mode)) {
      node.put("type","FILE");MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];
      try(var input=new FileInputStream(file)){int n;while((n=input.read(buffer))!=-1)hash.update(buffer,0,n);}
      StringBuilder value=new StringBuilder();String hex="0123456789abcdef";for(byte b:hash.digest()){value.append(hex.charAt((b&255)>>>4));value.append(hex.charAt(b&15));}node.put("sha256",value.toString());
    } else throw new IllegalStateException("SPECIAL:"+relative);
    nodes.put(relative,node);
  }
}
