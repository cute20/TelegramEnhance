package io.github.telegramenhance.library;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.library.LibraryModels.*;
import io.github.telegramenhance.telegram.TelegramService;
import java.util.List;
import org.junit.jupiter.api.Test;

class LibraryCatalogServiceTest {
    @Test void unauthenticatedReadCannotReachTheRepository() {
        var repository = mock(ResourceRepository.class); var telegram = mock(TelegramService.class);
        when(telegram.accountId()).thenThrow(new IllegalArgumentException("尚未授权"));
        var service = new LibraryCatalogService(repository, telegram);
        assertThrows(IllegalArgumentException.class, service::channels);
        assertThrows(IllegalArgumentException.class, () -> service.catalog(null, "", "", 20, 0));
        assertThrows(IllegalArgumentException.class, () -> service.media("post", null, true, 50, 0));
        verifyNoInteractions(repository);
    }
    @Test void accountChangingDuringAReadCannotReturnThePreviousAccountsData() {
        var repository = mock(ResourceRepository.class); var telegram = mock(TelegramService.class);
        when(telegram.accountId()).thenReturn("account-a", "account-b");
        when(repository.channels("account-a")).thenReturn(List.of(new Channel("-1001", "private synthetic channel", 1)));
        assertThrows(IllegalArgumentException.class, () -> new LibraryCatalogService(repository, telegram).channels());
    }
    @Test void catalogUsesTheCurrentAccountAndSharedNormalization() {
        var repository = mock(ResourceRepository.class); var telegram = mock(TelegramService.class);
        when(telegram.accountId()).thenReturn("account-a");
        var expected = new CatalogPage(List.of(), 20, 40, 0, 3, 2, false, "INDEXED_ONLY");
        var query = PostSearch.parse(List.of("-1001", "-1002"), "＃北京 #旅行", "CAFE，风景");
        when(repository.catalog("account-a", query, 20, 40)).thenReturn(expected);
        assertSame(expected, new LibraryCatalogService(repository, telegram).catalog(List.of("-1001", "-1002"), "＃北京 #旅行", "CAFE，风景", 20, 40));
    }
    @Test void mediaTypesNormalizeIndependentlyOfTheMainMediaSwitch() {
        var repository = mock(ResourceRepository.class); var telegram = mock(TelegramService.class);
        when(telegram.accountId()).thenReturn("account-a");
        var expected = new MediaPage(List.of(), 50, 0, 0, false, 0, 0, "INDEXED_ONLY");
        when(repository.media("account-a", "post", List.of("PHOTO", "VIDEO"), false, 50, 0)).thenReturn(expected);
        assertSame(expected, new LibraryCatalogService(repository, telegram).media("post", List.of(" photo ", "VIDEO", "PHOTO"), false, 50, 0));
    }
    @Test void invalidFiltersCannotTriggerRepositoryQueries() {
        var repository = mock(ResourceRepository.class); var telegram = mock(TelegramService.class);
        var service = new LibraryCatalogService(repository, telegram);
        assertThrows(IllegalArgumentException.class, () -> service.media("post", List.of("PHOTO OR 1=1"), true, 50, 0));
        assertThrows(IllegalArgumentException.class, () -> service.catalog(List.of("foreign-expression"), "", "", 20, 0));
        verifyNoInteractions(repository);
    }
}
