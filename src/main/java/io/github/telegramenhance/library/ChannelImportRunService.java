package io.github.telegramenhance.library;

import io.github.telegramenhance.library.ChannelImportRunModels.RunView;
import io.github.telegramenhance.telegram.ChannelReader;
import io.github.telegramenhance.telegram.ResourceReader;
import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.transfer.ScanTaskStore;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** One explicit HTTP step per bounded source page or persisted candidate; no background loop. */
@Service
public final class ChannelImportRunService {
    private final ResourceService work;
    private final ChannelImportRunRepository repository;
    private final ChannelReader channels;
    private final ResourceReader posts;
    private final TelegramService telegram;
    private ScanTaskStore scanTasks;
    @org.springframework.beans.factory.annotation.Autowired public void trackScans(ScanTaskStore scanTasks){this.scanTasks=scanTasks;}

    public ChannelImportRunService(ResourceService work, ChannelImportRunRepository repository,
            ChannelReader channels, ResourceReader posts, TelegramService telegram) {
        this.work = work; this.repository = repository; this.channels = channels; this.posts = posts; this.telegram = telegram;
    }

    public RunView state(String channelId) {
        channel(channelId); Session session = capture();
        RunView view = repository.state(session.account(), channelId); requireCurrent(session);return view;
    }

    public CompletableFuture<RunView> start(String channelId, boolean restart, String runId, Long revision) {
        channel(channelId);
        if (revision != null && revision < 0) throw new IllegalArgumentException("频道导入版本无效");
        return read(session -> {
            var managed = channels.managedChannel(channelId);
            if (managed == null || !channelId.equals(managed.id())) throw new IllegalArgumentException("未取得当前账号管理的原频道，未开始导入");
            requireCurrent(session);
            var previous=repository.state(session.account(),channelId);
            var result=repository.start(session.account(), channelId, restart, runId, revision, () -> requireCurrent(session));
            if(scanTasks!=null&&previous!=null&&!previous.runId().equals(result.runId()))scanTasks.expire(session.account(),session.generation(),"import",previous.runId(),"已显式重新开始导入，原检查点仅保留历史");
            remember(session,result,true,null);return result;
        });
    }

    public CompletableFuture<RunView> step(String channelId, String runId, Long revision) {
        channel(channelId);
        if (runId == null || runId.isBlank() || runId.length() > 64 || revision == null || revision < 0) throw new IllegalArgumentException("频道导入检查点参数无效");
        return read(session -> {
            var state = repository.current(session.account(), channelId);
            ChannelImportRunRepository.expect(state, runId, revision, true);
            if(scanTasks!=null)scanTasks.check(session.account(),session.generation(),"import",runId);
            try(var admitted=scanTasks==null?null:scanTasks.begin(session.account(),session.generation(),"import",runId)){
            if (state.pending().isEmpty()) {
                var page = channels.historyPage(channelId, state.sourceCursor());
                requireCurrent(session);
                var result=repository.batch(session.account(), state, page, () -> requireCurrent(session));remember(session,result,false,null);return result;
            }
            var candidate = state.pending().get(state.offset());
            var post = posts.channelPost(channelId, candidate.messageId());
            requireCurrent(session);
            String expectedAlbum = candidate.albumId() == null ? "0" : candidate.albumId();
            if (post == null || !session.account().equals(post.accountId()) || !channelId.equals(post.channelId())
                    || !expectedAlbum.equals(post.albumId() == null ? "0" : post.albumId())
                    || "0".equals(expectedAlbum) && !candidate.messageId().equals(post.mainMessageId())
                    || post.mainMessages().stream().noneMatch(message -> channelId.equals(message.chatId()) && candidate.messageId().equals(message.messageId()))) {
                throw new IllegalArgumentException("取得的主帖不属于当前频道候选，检查点未推进");
            }
            var result=repository.commit(session.account(), state, post, () -> requireCurrent(session));remember(session,result,false,null);return result;
            }catch(RuntimeException failure){if(!(failure instanceof ScanTaskStore.Paused))remember(session,repository.state(session.account(),channelId),false,"本次导入读取未完成，原检查点保留；请核对后显式继续");throw failure;}
        });
    }

    private void remember(Session session,RunView view,boolean claim,String error){if(scanTasks==null||view==null)return;var previous=scanTasks.find(session.account(),session.generation(),"import",view.runId());long now=System.currentTimeMillis();String state=view.exhausted()?"COMPLETED":error!=null||view.status().equals("BLOCKED")?"FAILED":"WAITING_STEP";
        scanTasks.remember(session.account(),session.generation(),new ScanTaskStore.Snapshot(view.runId(),"import","频道导入",view.channelId(),state,previous==null?now:previous.createdAt(),now,0,view.processed(),0,"IMPORTING",view.channelId(),null,error==null?view.warning():error,view.revision(),0,true,view.status().equals("READY")),claim);
    }

    private Session capture() {
        Session session = new Session(telegram.accountId(), telegram.sessionGeneration()); requireCurrent(session); return session;
    }
    private <T> CompletableFuture<T> read(Function<Session, T> action) {
        Session session = capture();
        return work.readWork(() -> {
            requireCurrent(session); T result = action.apply(session); requireCurrent(session); return result;
        });
    }
    private void requireCurrent(Session session) {
        if (!Objects.equals(session.account(), telegram.accountId()) || session.generation() != telegram.sessionGeneration()) {
            throw new IllegalArgumentException("Telegram 账号或会话已变化，请刷新进度后重试");
        }
    }
    private static void channel(String value) {
        try {
            if (value == null || value.length() > 20 || Long.parseLong(value) == 0 || !Long.toString(Long.parseLong(value)).equals(value)) throw new NumberFormatException();
        } catch (NumberFormatException failure) { throw new IllegalArgumentException("频道标识无效"); }
    }
    private record Session(String account, long generation) { }
}
