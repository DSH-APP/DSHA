import android.system.*;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.util.DocumentPaths;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Only synthetic files beneath the supplied, previously created fixture root. */
public final class DocumentParentFixture {
    static int checks;
    static final AndroidBackupFileSystem fs=new AndroidBackupFileSystem();
    static void check(boolean ok,String name) { if(!ok)throw new AssertionError(name); checks++; }
    static void put(File file,String value)throws IOException {try(FileOutputStream out=new FileOutputStream(file)){out.write(value.getBytes(StandardCharsets.UTF_8));}}
    static String read(File file)throws IOException {try(FileInputStream in=new FileInputStream(file)){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    static String link(File file)throws IOException {try{return Os.readlink(file.getPath());}catch(ErrnoException error){if(error.errno==OsConstants.EINVAL||error.errno==OsConstants.ENOENT)return null;throw new IOException(error);}}
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("fixture root required");
        File root=new File(args[0]).getCanonicalFile();
        if(!root.getPath().matches("/data/local/tmp/dsha-r2-documents-[0-9a-f]{32}")||!root.isDirectory())throw new IllegalArgumentException("unexpected fixture root");
        int before=new File("/proc/self/fd").list().length;
        for(String op:new String[]{"create","directory","rename","delete"}){
            for(boolean guarded:new boolean[]{false,true}){
                File test=new File(root,op+(guarded?"-guarded":"-legacy"));check(test.mkdir(),"new case");
                File base=new File(test,"base"),outside=new File(test,"outside"),parent=new File(base,"selected");
                check(base.mkdir()&&outside.mkdir()&&parent.mkdir(),"new trees");
                put(new File(parent,"original.txt"),"original-selected-by-user");
                put(new File(outside,"original.txt"),"external-synthetic-sentinel");
                DocumentPaths paths=new DocumentPaths(base,DocumentParentFixture::link);
                File selected=paths.resolve("selected/"+(op.equals("create")?"created.txt":op.equals("directory")?"created-dir":"original.txt"),false);
                File renamed=paths.resolve("selected/renamed.txt",false);
                // Deterministic boundary: the production resolver has returned; no mutation has happened yet.
                check(parent.renameTo(new File(base,"held")),"retain original parent");Os.symlink(outside.getPath(),parent.getPath());
                boolean rejected=false;
                try {
                    if(op.equals("create")){if(guarded){try(OutputStream out=fs.create(selected)){} }else check(selected.createNewFile(),"legacy create");}
                    else if(op.equals("directory")){if(guarded)fs.directory(selected);else check(selected.mkdir(),"legacy directory");}
                    else if(op.equals("rename")){if(guarded)fs.move(selected,renamed);else check(selected.renameTo(renamed),"legacy rename");}
                    else {if(guarded)fs.delete(selected);else check(selected.delete(),"legacy delete");}
                } catch(IOException failure){rejected=true;}
                if(guarded){check(rejected,"guard rejects changed parent");check(read(new File(outside,"original.txt")).equals("external-synthetic-sentinel"),"outside sentinel unchanged");check(!new File(outside,"created.txt").exists()&&!new File(outside,"created-dir").exists()&&!new File(outside,"renamed.txt").exists(),"no outside publication");}
                else {check(!rejected,"legacy follows substituted parent");check(op.equals("delete")?!new File(outside,"original.txt").exists():op.equals("rename")?new File(outside,"renamed.txt").isFile():new File(outside,op.equals("create")?"created.txt":"created-dir").exists(),"legacy outside effect observed");}
                System.out.println(op+" "+(guarded?"GUARDED_REJECTED":"LEGACY_REDIRECT_CONFIRMED"));
            }
        }
        File normal=new File(root,"normal");check(normal.mkdir(),"normal root");
        File plain=new File(normal,"plain.txt"),renamed=new File(normal,"renamed.txt"),dir=new File(normal,"folder");
        try(OutputStream out=fs.create(plain)){out.write("safe-normal".getBytes(StandardCharsets.UTF_8));}
        fs.directory(dir);fs.move(plain,renamed);check(read(renamed).equals("safe-normal"),"normal rename bytes");
        File symbolic=new File(normal,"link");Os.symlink(renamed.getPath(),symbolic.getPath());fs.delete(symbolic);
        check(link(symbolic)==null&&read(renamed).equals("safe-normal"),"delete link retains target");
        fs.delete(renamed);fs.delete(dir);check(!renamed.exists()&&!dir.exists(),"normal delete");
        int after=new File("/proc/self/fd").list().length;check(after<=before+1,"descriptor balance");
        System.out.println("PASS checks="+checks+" fdBefore="+before+" fdAfter="+after+" uid="+android.os.Process.myUid());
    }
}
