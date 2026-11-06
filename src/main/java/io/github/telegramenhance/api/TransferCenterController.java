package io.github.telegramenhance.api;

import io.github.telegramenhance.transfer.TransferCenterService;
import io.github.telegramenhance.transfer.TransferCenterModels.*;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/transfers/center")
public final class TransferCenterController {
    private final TransferCenterService center;
    public TransferCenterController(TransferCenterService center){this.center=center;}
    @GetMapping public Page list(@RequestParam(defaultValue="all")String kind,@RequestParam(defaultValue="all")String status,
                                @RequestParam(defaultValue="20")int limit,@RequestParam(defaultValue="0")int offset){return center.list(kind,status,limit,offset);}
    @PostMapping("/action") public CompletableFuture<ActionResult> action(@RequestBody Action input){return center.action(input);}
    @GetMapping("/scans/{historyId}") public io.github.telegramenhance.transfer.ScanTaskStore.View scan(@PathVariable String historyId){return center.scanDetail(historyId);}
    @PostMapping("/pause-all") public PauseReceipt pause(@RequestBody PauseRequest input){return center.pauseAll(input);}
    @GetMapping("/pause-all/{operation}") public PauseReceipt receipt(@PathVariable String operation){return center.receipt(operation);}
}
