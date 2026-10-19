package com.localcaption.instant;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** LiteRT picks the first dispatch library in a directory: expose only the current vendor. */
final class LiteRtLibraries {
    static String forDevice(Context c)throws IOException{
        String chip=DeviceProfile.detect(c).npuChip;
        return forVendor(c,chip.startsWith("SM")?"Qualcomm":chip.startsWith("MT")?"MediaTek":chip.startsWith("Tensor")?"GoogleTensor":"");
    }
    static synchronized String forVendor(Context c,String vendor)throws IOException{
        if(!vendor.equals("Qualcomm")&&!vendor.equals("MediaTek")&&!vendor.equals("GoogleTensor"))throw new IOException("No LiteRT runtime for this processor");
        File nativeDir=new File(c.getApplicationInfo().nativeLibraryDir);
        File directory=new File(c.getCodeCacheDir(),"litert-plugins/"+BuildConfig.VERSION_CODE+"/"+vendor);
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot prepare NPU library directory");
        File[] libraries=nativeDir.listFiles();if(libraries==null)throw new IOException("NPU libraries unavailable");
        for(File library:libraries){
            String name=library.getName();if(!name.endsWith(".so"))continue;
            if((name.startsWith("libLiteRtDispatch_")||name.startsWith("libLiteRtCompilerPlugin_"))&&!name.endsWith("_"+vendor+".so"))continue;
            Path link=new File(directory,name).toPath(),source=library.toPath();
            if(Files.isSymbolicLink(link)&&Files.readSymbolicLink(link).equals(source))continue;
            if(Files.isDirectory(link,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new IOException("Unexpected NPU library directory");
            Files.deleteIfExists(link);Files.createSymbolicLink(link,source);
        }
        if(!new File(directory,"libLiteRtDispatch_"+vendor+".so").isFile())throw new IOException("Missing NPU dispatch library");
        return directory.getAbsolutePath();
    }
}
