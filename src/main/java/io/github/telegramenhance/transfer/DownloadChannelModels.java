package io.github.telegramenhance.transfer;
import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.library.ChannelImportModels.ManagedChannel;
import java.util.List;

/** Metadata-only preview. All Telegram identities stay strings. */
public final class DownloadChannelModels {
    private DownloadChannelModels(){}
    public record Input(String source,String tags,String keywords){@Override public String toString(){return "DownloadChannelInput[local source and filters]";}}
    public record Position(long revision){}
    public record Row(Post post,String match,String postLink,String comments){ }
    public record View(String id,String accountId,ManagedChannel channel,List<String> tags,List<String> keywords,
            long revision,int scanned,int matched,int unverified,List<Row> rows,boolean exhausted,
            boolean canContinue,boolean busy,boolean limitReached,String error,List<String> warnings,
            long createdAt,long expiresAt){
        public View{tags=List.copyOf(tags);keywords=List.copyOf(keywords);rows=List.copyOf(rows);warnings=List.copyOf(warnings);}
        @Override public String toString(){return "DownloadChannelPreview[scanned="+scanned+", matched="+matched+", exhausted="+exhausted+"]";}
    }
}
