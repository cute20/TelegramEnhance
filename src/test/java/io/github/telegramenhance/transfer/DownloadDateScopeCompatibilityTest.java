package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DownloadDateScopeCompatibilityTest {
    private final JsonMapper json=JsonMapper.builder().build();

    @Test void oldInputAndPreviewJsonKeepTheirUnfilteredMeaning(){
        var input=json.readValue("""
                {"source":"-1001","tags":"","keywords":""}
                """,DownloadChannelModels.Input.class);
        assertEquals(new DownloadChannelModels.Input("-1001","",""),input);assertNull(input.startDate());assertNull(input.endDate());
        var view=json.readValue("""
                {"id":"scan","accountId":"1","channel":{"id":"-1001","title":"source"},
                 "tags":[],"keywords":[],"revision":1,"scanned":0,"matched":0,"unverified":0,"rows":[],
                 "exhausted":false,"canContinue":true,"busy":false,"limitReached":false,"error":null,
                 "warnings":[],"createdAt":1,"expiresAt":600001}
                """,DownloadChannelModels.View.class);
        assertNull(view.startDate());assertNull(view.endDate());assertTrue(view.canContinue());
    }

    @Test void legacyFrozenSnapshotJsonAndConstructorsRemainReadableAndNewDatesRoundTrip(){
        var old=json.readValue("""
                {"previewId":"preview","revision":3,"channelTitle":"source","tags":[],"keywords":[],
                 "types":["DOCUMENT"],"includeMain":false,"targetDirectory":"C:/synthetic",
                 "postCount":21,"total":1050,"totalBytes":38850}
                """,DownloadBatchModels.Snapshot.class);
        assertNull(old.startDate());assertNull(old.endDate());assertEquals(1050,old.total());
        assertEquals(new DownloadBatchModels.Snapshot("preview",3,"source",List.of(),List.of(),List.of("DOCUMENT"),false,"C:/synthetic",21,1050,38850),old);
        var filtered=new DownloadBatchModels.Snapshot(old.previewId(),old.revision(),old.channelTitle(),old.tags(),old.keywords(),old.types(),old.includeMain(),old.targetDirectory(),old.postCount(),old.total(),old.totalBytes(),"2026-11-01","2026-11-02");
        assertEquals(filtered,json.readValue(json.writeValueAsString(filtered),DownloadBatchModels.Snapshot.class));
        var view=json.readValue("""
                {"id":"preview","accountId":"1","revision":3,"channelTitle":"source","tags":[],"keywords":[],
                 "types":["DOCUMENT"],"includeMain":false,"targetDirectory":"C:/synthetic","posts":[],
                 "total":0,"totalBytes":0,"exhausted":false,"canContinue":true,"busy":false,"error":null,
                 "warnings":[],"createdAt":1,"expiresAt":600001}
                """,DownloadBatchModels.View.class);
        assertNull(view.startDate());assertNull(view.endDate());
    }

    @Test void leapDatesAndBeijingBoundaryComparisonAreStrict(){
        var range=DownloadDateRange.parse("2024-02-29","2024-02-29");
        assertTrue(range.matches(java.time.Instant.parse("2024-02-28T16:00:00Z").toEpochMilli()));
        assertTrue(range.matches(java.time.Instant.parse("2024-02-29T15:59:59.999Z").toEpochMilli()));
        assertFalse(range.matches(java.time.Instant.parse("2024-02-29T16:00:00Z").toEpochMilli()));
        assertNull(range.matches(null));assertTrue(DownloadDateRange.parse(null,null).matches(null));
    }
}
