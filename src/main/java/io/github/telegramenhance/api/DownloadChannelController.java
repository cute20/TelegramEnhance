package io.github.telegramenhance.api;
import io.github.telegramenhance.transfer.*;
import io.github.telegramenhance.transfer.DownloadChannelModels.*;
import io.github.telegramenhance.library.ChannelImportModels.ManagedChannels;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/transfers/download/channels")
public final class DownloadChannelController{
    private final DownloadChannelService service;
    public DownloadChannelController(DownloadChannelService service){this.service=service;}
    @GetMapping public CompletableFuture<ManagedChannels> channels(){return service.channels();}
    @PostMapping("/preview")public CompletableFuture<View> start(@RequestBody Input input){return service.start(input);}
    @GetMapping("/preview/{id}")public View get(@PathVariable String id){return service.get(id);}
    @PostMapping("/preview/{id}/next")public CompletableFuture<View> next(@PathVariable String id,@RequestBody Position position){return service.next(id,position);}
}
