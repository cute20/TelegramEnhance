package io.github.telegramenhance.rules;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.telegram.ResourceReader;
import io.github.telegramenhance.telegram.TelegramService;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
class RuleDefaultsServiceTest{
    static final String LINK="https://t.me/synthetic/1";
    final ResourceService reads=mock(ResourceService.class);final ResourceReader reader=mock(ResourceReader.class);final TelegramService telegram=mock(TelegramService.class);
    RuleDefaultsService service(){when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);when(reads.readWork(any())).thenAnswer(i->CompletableFuture.completedFuture(((Supplier<?>)i.getArgument(0)).get()));return new RuleDefaultsService(reads,reader,telegram);}
    Post post(String title){return new Post("p","1","-1001","channel","100",title,"text","0",List.of(),List.of(),null,null,List.of(),false,"PARTIAL",List.of());}
    @Test void readsManagedPostNameThroughSharedQueueWithoutIndexing()throws Exception{var s=service();when(reader.probe(LINK)).thenReturn(post("主帖 名称"));var result=s.name(LINK).get();assertEquals("主帖 名称",result.name());assertEquals("1",result.accountId());assertEquals(LINK,result.link());verify(reads).readWork(any());verify(reader).probe(LINK);verifyNoMoreInteractions(reader);}
    @Test void longNamesKeepSurrogatePairsIntactAndEmptyNameHasFallback()throws Exception{var s=service();when(reader.probe(LINK)).thenReturn(post("a".repeat(79)+"😀"));assertEquals("a".repeat(79),s.name(LINK).get().name());when(reader.probe(LINK)).thenReturn(post(" "));assertEquals("主帖 100",s.name(LINK).get().name());}
    @Test void invalidUrlIsRejectedBeforeQueueing(){var s=service();assertThrows(IllegalArgumentException.class,()->s.name("https://example.com/a"));verifyNoInteractions(reader);verify(reads,never()).readWork(any());}
    @Test void sessionChangeAfterReadingRejectsName(){var s=service();when(reader.probe(LINK)).thenAnswer(i->{when(telegram.sessionGeneration()).thenReturn(2L);return post("old");});assertThrows(IllegalArgumentException.class,()->s.name(LINK));}
}
