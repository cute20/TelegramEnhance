package io.github.telegramenhance.api;

import io.github.telegramenhance.transfer.DownloadRuleTaskService;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/transfers/download/rule-tasks")
public final class DownloadRuleTaskController {
    private final DownloadRuleTaskService service;
    public DownloadRuleTaskController(DownloadRuleTaskService service){this.service=service;}
    @GetMapping public DownloadRuleTaskService.Listing list(){return service.list();}
    @GetMapping("/{id}") public DownloadRuleTaskService.Group get(@PathVariable String id){return service.get(id);}
    @GetMapping("/{id}/members") public DownloadRuleTaskService.Members members(@PathVariable String id,@RequestParam(defaultValue="10") int limit,@RequestParam(defaultValue="0") int offset){return service.members(id,limit,offset);}
    @GetMapping("/{id}/files") public DownloadRuleTaskService.FilesPage files(@PathVariable String id,@RequestParam String source,@RequestParam(defaultValue="50") int limit,@RequestParam(required=false) String cursor){return service.files(id,source,limit,cursor);}
}
