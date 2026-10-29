package io.github.telegramenhance.rules;

import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.telegram.ResourceReader;
import io.github.telegramenhance.telegram.TelegramService;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Service;

/** Reads a managed post name through the existing bounded lane, without indexing or sending. */
@Service
public final class RuleDefaultsService {
    private final ResourceService reads;private final ResourceReader reader;private final TelegramService telegram;
    public RuleDefaultsService(ResourceService reads,ResourceReader reader,TelegramService telegram){this.reads=reads;this.reader=reader;this.telegram=telegram;}
    public record Name(String accountId,String link,String name){}
    public CompletableFuture<Name> name(String value){
        String account=telegram.accountId();long generation=telegram.sessionGeneration();String link=RuleService.checkedLink(value);
        return reads.readWork(()->{
            current(account,generation);var post=reader.probe(link);current(account,generation);
            if(!account.equals(post.accountId()))throw new IllegalArgumentException("目标主帖与当前账号不一致");
            String title=post.title()==null?"":post.title().replaceAll("\\s+"," ").strip();if(title.isEmpty())title="主帖 "+post.mainMessageId();
            int end=Math.min(80,title.length());if(end<title.length()&&Character.isHighSurrogate(title.charAt(end-1)))end--;
            return new Name(account,link,title.substring(0,end));
        });
    }
    private void current(String account,long generation){if(Thread.currentThread().isInterrupted()||!account.equals(telegram.accountId())||generation!=telegram.sessionGeneration())throw new IllegalArgumentException("账号或会话已变化，请重新读取主帖名称");}
}
