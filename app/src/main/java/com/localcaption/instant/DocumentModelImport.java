package com.localcaption.instant;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Reads only the file or folder granted by the system picker. Hash checks run before install. */
final class DocumentModelImport {
    private int visited;
    private final Map<String,Uri> found=new HashMap<>();
    static Map<String,Uri> find(Context c,Uri tree,Set<String> names)throws IOException{
        if(!"content".equals(tree.getScheme()))throw new IOException("Select a model file or folder");
        if(!DocumentsContract.isTreeUri(tree)){
            if(names.size()!=1||!DocumentsContract.isDocumentUri(c,tree))throw new IOException("Select an extracted model folder");
            Map<String,Uri> file=new HashMap<>();file.put(names.iterator().next(),tree);return file;
        }
        DocumentModelImport scan=new DocumentModelImport();scan.walk(c,tree,DocumentsContract.getTreeDocumentId(tree),names,0);return scan.found;
    }
    private void walk(Context c,Uri tree,String parent,Set<String> names,int depth)throws IOException{
        if(depth>4)throw new IOException("Folder nesting is too deep");
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent);
        String[] columns={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE};
        try(Cursor cursor=c.getContentResolver().query(children,columns,null,null,null)){
            if(cursor==null)throw new IOException("Cannot read folder");
            while(cursor.moveToNext()){
                if(ModelStore.cancel)throw new java.util.concurrent.CancellationException();
                if(++visited>1024)throw new IOException("Select a folder containing only this model");
                String id=cursor.getString(0),name=cursor.getString(1),type=cursor.getString(2);
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(type))walk(c,tree,id,names,depth+1);
                else if(names.contains(name)&&found.put(name,DocumentsContract.buildDocumentUriUsingTree(tree,id))!=null)throw new IOException("Duplicate model file: "+name);
            }
        }
    }
}
