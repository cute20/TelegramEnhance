package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.transfer.UploadPreviewModels.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class UploadGroupByteLimitTest {
    private static final long GIB=1024L*1024*1024;
    @Test void largeFilesSplitBeforeTheSendersEightGiBBoundaryRatherThanFailingAfterTenFileGrouping(){
        var files=new ArrayList<FileVersion>();for(int i=1;i<=10;i++)files.add(file("clip"+i+".mp4","VIDEO",GIB+1));
        var groups=UploadPreviewService.groups(files,"FILENAME");
        assertEquals(List.of(7,3),groups.stream().map(g->g.files().size()).toList());
        assertEquals(files.stream().map(FileVersion::relativePath).toList(),groups.stream().flatMap(g->g.files().stream()).map(Member::relativePath).toList());
        assertTrue(groups.stream().allMatch(g->g.files().stream().mapToLong(Member::size).sum()<=8*GIB));
        assertEquals("clip8.mp4",groups.get(1).files().getFirst().caption());
    }
    @Test void anExactBoundaryFitsButTypesTenMemberLimitAndSingleAnimationsStillApply(){
        var files=new ArrayList<FileVersion>();files.add(file("image.jpg","PHOTO",100));
        for(int i=0;i<4;i++)files.add(file("large"+i+".mp4","VIDEO",2*GIB));
        files.add(file("tail.mp4","VIDEO",1));for(int i=0;i<12;i++)files.add(file("doc"+i+".txt","DOCUMENT",1));
        files.add(file("a.gif","ANIMATION",1));files.add(file("b.gif","ANIMATION",1));
        var groups=UploadPreviewService.groups(files,"EMPTY");
        assertEquals(List.of(1,4,1,10,2,1,1),groups.stream().map(g->g.files().size()).toList());
        assertEquals(List.of("PHOTO","VIDEO","VIDEO","DOCUMENT","DOCUMENT","ANIMATION","ANIMATION"),groups.stream().map(Group::mediaType).toList());
        for(var group:groups){assertTrue(group.files().stream().allMatch(m->m.mediaType().equals(group.mediaType())&&m.caption().isEmpty()));assertEquals(group.files().size()==1?"SINGLE":"ALBUM",group.mode());}
    }
    @Test void anOversizedMemberIsRejectedBeforeAnyUploadCanBePrepared(){
        assertThrows(IllegalArgumentException.class,()->UploadPreviewService.groups(List.of(file("huge.mp4","VIDEO",8*GIB+1)),"EMPTY"));
        assertThrows(IllegalArgumentException.class,()->UploadPreviewService.groups(List.of(file("huge.mp4","VIDEO",Long.MAX_VALUE)),"EMPTY"));
    }
    @Test void customGroupSizesKeepTheByteBoundaryAndAnimationsRemainSingle(){
        var files=new ArrayList<FileVersion>();
        for(int i=1;i<=5;i++)files.add(file("clip"+i+".mp4","VIDEO",3*GIB));
        for(int i=1;i<=5;i++)files.add(file("doc"+i+".txt","DOCUMENT",1));
        files.add(file("a.gif","ANIMATION",1));files.add(file("b.gif","ANIMATION",1));
        var groups=UploadPreviewService.groups(files,"FILENAME",3);
        assertEquals(List.of(2,2,1,3,2,1,1),groups.stream().map(g->g.files().size()).toList());
        assertTrue(groups.stream().allMatch(g->g.files().size()<=3&&g.files().stream().mapToLong(Member::size).sum()<=8*GIB));
        assertThrows(IllegalArgumentException.class,()->UploadPreviewService.groups(files,"EMPTY",0));
        assertThrows(IllegalArgumentException.class,()->UploadPreviewService.groups(files,"EMPTY",11));
    }
    private static FileVersion file(String path,String type,long size){return new FileVersion(path,type,size,1,"synthetic-file-id","0".repeat(64));}
}
