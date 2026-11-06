package io.github.telegramenhance.desktop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.telegramenhance.api.WorkbenchController;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.storage.SettingsService;
import io.github.telegramenhance.telegram.TelegramService;
import java.net.URI;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkbenchTrayTest {
    @TempDir Path directory;
    private static final URI LAUNCH = URI.create("http://127.0.0.1:43127/#token=synthetic-private-token");

    @Test void defaultsAreDisabledAndReadingOrStartingNeverWritesSettings() {
        try (var db = new Database(new AppPaths(directory))) {
            var platform = new FakeTray();
            try (var tray = tray(db, platform)) {
                tray.started();
                assertEquals(new WorkbenchTray.View(false, true, false, ""), tray.view());
                assertEquals(0, platform.installs); assertEquals(0, settingsCount(db));
                assertThrows(IllegalArgumentException.class, () -> tray.save(new WorkbenchTray.Update(null)));
                assertEquals(0, settingsCount(db));
            }
        }
    }

    @Test void explicitPreferenceRestoresAfterReadyAndDisablingRemovesTheIcon() {
        try (var db = new Database(new AppPaths(directory))) {
            db.write(c -> { try (var q = c.createStatement()) { q.executeUpdate("INSERT INTO app_settings VALUES('opaque','preserve',123)"); } return null; });
            var platform = new FakeTray();
            try (var tray = tray(db, platform)) {
                assertFalse(tray.save(new WorkbenchTray.Update(true)).trayActive(), "No icon before the server is ready");
                assertTrue(tray.view().trayEnabled()); tray.started();
                assertTrue(tray.view().trayActive()); assertEquals(1, platform.installs);
                tray.started(); assertEquals(1, platform.installs);
            }
            assertFalse(platform.active());
            var restored = new FakeTray();
            try (var tray = tray(db, restored)) {
                assertTrue(tray.view().trayEnabled()); assertFalse(tray.view().trayActive());
                tray.started(); assertTrue(tray.view().trayActive());
                assertFalse(tray.save(new WorkbenchTray.Update(false)).trayActive());
                assertFalse(tray.view().trayEnabled());
            }
            try (var tray = tray(db, new FakeTray())) { tray.started(); assertFalse(tray.view().trayActive()); }
            assertEquals("preserve:123", db.read(c -> { try (var r = c.createStatement().executeQuery(
                    "SELECT setting_value||':'||updated_at FROM app_settings WHERE setting_key='opaque'")) { r.next(); return r.getString(1); } }));
            assertEquals(2, settingsCount(db));
        }
    }

    @Test void unsupportedDesktopAndInstallFailureLeaveTheBackendUsableWithoutAnIcon() {
        try (var db = new Database(new AppPaths(directory))) {
            var unsupported = new FakeTray(); unsupported.supported = false;
            try (var tray = tray(db, unsupported)) {
                tray.started(); var saved = tray.save(new WorkbenchTray.Update(true));
                assertTrue(saved.trayEnabled()); assertFalse(saved.traySupported()); assertFalse(saved.trayActive());
                assertFalse(saved.warning().isBlank()); assertEquals(0, unsupported.installs);
            }
            var broken = new FakeTray(); broken.failInstall = true;
            try (var tray = tray(db, broken)) {
                assertDoesNotThrow(tray::started);
                assertTrue(tray.view().traySupported()); assertFalse(tray.view().trayActive()); assertFalse(tray.view().warning().isBlank());
                broken.failInstall = false;
                assertTrue(tray.save(new WorkbenchTray.Update(true)).trayActive());
                assertEquals("", tray.view().warning());
            }
        }
    }

    @Test void menuOpensOnlyTheSafeLaunchUriAndNormalExitRemovesTheIconBeforeClosingContext() throws Exception {
        try (var db = new Database(new AppPaths(directory))) {
            var platform = new FakeTray(); var shutdown = new CountDownLatch(1); var shutdownCalls = new AtomicInteger();
            var tray = new WorkbenchTray(db, platform, () -> LAUNCH, () -> {
                assertFalse(platform.active()); shutdownCalls.incrementAndGet(); shutdown.countDown();
            });
            try {
                tray.started(); tray.save(new WorkbenchTray.Update(true));
                assertFalse(tray.view().toString().contains("synthetic-private-token"));
                platform.open.run(); assertTrue(platform.browsed.await(5, TimeUnit.SECONDS)); assertEquals(LAUNCH, platform.uri);
                platform.exit.run(); platform.exit.run();
                assertTrue(shutdown.await(5, TimeUnit.SECONDS)); assertEquals(1, shutdownCalls.get());
                assertFalse(tray.view().trayActive());
                assertTrue(tray.view().trayEnabled(), "Normal exit must not change the user's saved opt-in");
                tray.started(); assertEquals(1, platform.installs);
                assertThrows(IllegalArgumentException.class, () -> tray.save(new WorkbenchTray.Update(true)));
            } finally { tray.close(); }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void enabledTrayIsRemovedBeforeBlockingContextDestructionWithoutDisablingItsPreference(boolean viaHttp) throws Exception {
        var paths = new AppPaths(directory);
        try (var db = new Database(paths)) {
            var platform = new FakeTray(); var destroyEntered = new CountDownLatch(1); var allowDestroy = new CountDownLatch(1);
            var destroyFinished = new CountDownLatch(1); var context = new AnnotationConfigApplicationContext();
            var tray = new WorkbenchTray(db, platform, () -> LAUNCH, context::close);
            context.registerBean("workbenchTray", WorkbenchTray.class, () -> tray);
            // This singleton is created after the tray and destroyed before it. Waiting
            // for @PreDestroy alone would leave the tray visible throughout this wait.
            context.registerBean("blockingShutdown", BlockingShutdown.class, () -> new BlockingShutdown(destroyEntered, allowDestroy, destroyFinished));
            context.refresh(); tray.started(); tray.save(new WorkbenchTray.Update(true));
            CompletableFuture<Void> closing = null;
            try {
                assertTrue(platform.active()); assertTrue(tray.view().trayEnabled());
                if (viaHttp) {
                    var controller = new WorkbenchController(mock(SettingsService.class), mock(TelegramService.class), db, paths, context, null, tray);
                    MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/app/exit").contentType(MediaType.APPLICATION_JSON).content("{}"))
                            .andExpect(status().isOk());
                    assertFalse(platform.active(), "The page exit command must remove the icon before its response returns");
                } else {
                    closing = CompletableFuture.runAsync(context::close);
                }
                assertTrue(destroyEntered.await(5, TimeUnit.SECONDS));
                assertFalse(platform.active(), "An enabled tray must already be removed while another bean is still stopping");
                assertEquals(1, allowDestroy.getCount()); assertTrue(tray.view().trayEnabled());
                try (var restored = tray(db, new FakeTray())) {
                    assertTrue(restored.view().trayEnabled()); restored.started(); assertTrue(restored.view().trayActive());
                }
            } finally {
                allowDestroy.countDown(); assertTrue(destroyFinished.await(5, TimeUnit.SECONDS));
                if (closing != null) closing.get(5, TimeUnit.SECONDS);
                context.close(); tray.close();
            }
        }
    }

    private static final class BlockingShutdown implements AutoCloseable {
        private final CountDownLatch entered, allowed, finished;
        private BlockingShutdown(CountDownLatch entered, CountDownLatch allowed, CountDownLatch finished) { this.entered = entered; this.allowed = allowed; this.finished = finished; }
        @Override public void close() {
            entered.countDown();
            try { if (!allowed.await(10, TimeUnit.SECONDS)) throw new AssertionError("Shutdown regression did not release its synthetic blocker"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            finally { finished.countDown(); }
        }
    }

    @Test void removalFailureCanBeRetriedDuringContextShutdownWithoutReenablingOrDisablingThePreference() {
        try (var db = new Database(new AppPaths(directory))) {
            var platform = new FakeTray();
            try (var tray = tray(db, platform)) {
                tray.started(); tray.save(new WorkbenchTray.Update(true)); platform.failRemoveOnce = true;
                assertDoesNotThrow(tray::close); assertTrue(platform.active(), "Synthetic first native removal failed");
                assertDoesNotThrow(tray::close); assertFalse(platform.active(), "A later shutdown hook must retry native removal");
                tray.started(); assertEquals(1, platform.installs);
                assertThrows(IllegalArgumentException.class, () -> tray.save(new WorkbenchTray.Update(false)));
                try (var restored = tray(db, new FakeTray())) { assertTrue(restored.view().trayEnabled()); }
            }
        }
    }

    @Test void aMenuExitOnTheEdtNeverWaitsForTheTrayMonitorHeldByAnAwtCaller() throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(javaExecutable, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                EdtExitProbe.class.getName(), directory.resolve("edt-child").toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "EDT exit deadlocked with a caller holding the tray monitor");
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    public static final class EdtExitProbe {
        public static void main(String[] args) throws Exception {
            try (var db = new Database(new AppPaths(Path.of(args[0])))) {
                var platform = new FakeTray(); var shutdown = new CountDownLatch(1); var shutdownCalls = new AtomicInteger();
                try (var tray = new WorkbenchTray(db, platform, () -> LAUNCH, () -> { shutdownCalls.incrementAndGet(); shutdown.countDown(); })) {
                    tray.started(); tray.save(new WorkbenchTray.Update(true));
                    synchronized (tray) {
                        EventQueue.invokeAndWait(tray::requestExit);
                        EventQueue.invokeAndWait(tray::requestExit);
                        // The menu must return to AWT without taking this monitor. Its
                        // single close worker will acquire the monitor after we release it.
                        if (!platform.active()) throw new AssertionError("Exit removal ran while its monitor was held by another caller");
                        tray.started();
                        try { tray.save(new WorkbenchTray.Update(false)); throw new AssertionError("Exit gate accepted a new preference mutation"); }
                        catch (IllegalArgumentException expected) { }
                    }
                    if (!shutdown.await(5, TimeUnit.SECONDS) || shutdownCalls.get() != 1 || platform.active())
                        throw new AssertionError("Menu exit failed to remove the icon and shut down exactly once");
                    try (var restored = new WorkbenchTray(db, new FakeTray(), () -> LAUNCH, () -> {})) {
                        if (!restored.view().trayEnabled()) throw new AssertionError("Exiting changed the explicit enabled preference");
                    }
                }
            }
        }
    }

    @Test void isolatedHeadlessJvmReportsUnavailableAndExitsWithoutDesktopThreads() throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(javaExecutable, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                HeadlessProbe.class.getName()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Headless tray probe must not retain a desktop thread");
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    public static final class HeadlessProbe {
        public static void main(String[] args) throws Exception {
            var platform = new WorkbenchTray.AwtTrayPlatform();
            if (platform.supported() || platform.active()) throw new AssertionError("Headless desktop cannot have an active tray");
            platform.remove();
        }
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void swingMenuFontsCoverTheirLabelsAndPopupsDisposeWithoutInstallingATray() throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(javaExecutable, "-Djava.awt.headless=false", "-cp", System.getProperty("java.class.path"),
                MenuFontProbe.class.getName()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Disposed Swing popups must not retain AWT threads");
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    public static final class MenuFontProbe {
        public static void main(String[] args) throws Exception {
            var opened = new AtomicInteger(); var exited = new AtomicInteger();
            EventQueue.invokeAndWait(() -> {
                var menu = WorkbenchTray.AwtTrayPlatform.createMenu(opened::incrementAndGet, exited::incrementAndGet);
                try {
                    if (menu.getComponentCount() != 3 || menu.getFont() == null || !(menu.getComponent(1) instanceof javax.swing.JSeparator))
                        throw new AssertionError("Missing Swing menu structure or explicit font");
                    var open = (javax.swing.JButton) menu.getComponent(0); var exit = (javax.swing.JButton) menu.getComponent(2); Font font = menu.getFont();
                    for (var item : new javax.swing.JButton[]{open, exit}) {
                        if (!font.equals(item.getFont()) || font.canDisplayUpTo(item.getText()) != -1)
                            throw new AssertionError("Swing menu label has no explicit covering font: " + item.getText());
                        item.doClick();
                        Object enter = item.getInputMap().get(javax.swing.KeyStroke.getKeyStroke("ENTER"));
                        if (enter == null || item.getActionMap().get(enter) == null) throw new AssertionError("Focused menu item has no Enter action");
                        item.getActionMap().get(enter).actionPerformed(new ActionEvent(item, ActionEvent.ACTION_PERFORMED, "ENTER"));
                    }
                    if (open.getText().equals("打开资源台")) {
                        if (!exit.getText().equals("正常退出")) throw new AssertionError("Inconsistent menu language");
                        for (String logical : new String[]{Font.DIALOG, Font.DIALOG_INPUT, Font.SANS_SERIF, Font.SERIF, Font.MONOSPACED})
                            if (logical.equalsIgnoreCase(font.getFamily())) throw new AssertionError("Configured Chinese Swing menu font must be a physical face");
                    } else if (!open.getText().equals("Open workbench") || !exit.getText().equals("Exit")) {
                        throw new AssertionError("No readable fallback menu labels");
                    }
                } finally { menu.removeAll(); }
                assertPopupDisposesOnEscape();
                assertPopupDisposesOnFocusLost();
            });
            if (opened.get() != 2 || exited.get() != 2) throw new AssertionError("Click and Enter must each activate the focused menu item once");
        }

        private static void assertPopupDisposesOnEscape() {
            var popup = WorkbenchTray.AwtTrayPlatform.createPopup(0, 0, () -> {}, () -> {});
            try {
                popup.pack();
                if (!popup.isDisplayable() || popup.isVisible()) throw new AssertionError("Probe must create a peer without showing a menu");
                var root = popup.getRootPane();
                var key = root.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).get(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
                var action = key == null ? null : root.getActionMap().get(key);
                if (action == null) throw new AssertionError("Popup has no Escape disposal action");
                action.actionPerformed(new ActionEvent(root, ActionEvent.ACTION_PERFORMED, "escape"));
                if (popup.isDisplayable()) throw new AssertionError("Escape left a popup peer alive");
            } finally { popup.dispose(); }
        }

        private static void assertPopupDisposesOnFocusLost() {
            var popup = WorkbenchTray.AwtTrayPlatform.createPopup(0, 0, () -> {}, () -> {});
            try {
                popup.pack();
                if (!popup.isDisplayable() || popup.isVisible()) throw new AssertionError("Probe must create a peer without showing a menu");
                if (popup.getWindowFocusListeners().length == 0) throw new AssertionError("Popup has no focus-lost disposal handler");
                for (var listener : popup.getWindowFocusListeners()) listener.windowLostFocus(new WindowEvent(popup, WindowEvent.WINDOW_LOST_FOCUS));
                if (popup.isDisplayable()) throw new AssertionError("Lost focus left a popup peer alive");
            } finally { popup.dispose(); }
        }
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void removingARealTrayWithItsSwingMenuOpenDisposesThePopupAndLetsItsIsolatedJvmExit() throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(javaExecutable, "-Djava.awt.headless=false", "-cp", System.getProperty("java.class.path"),
                OpenTrayPopupExitProbe.class.getName()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Exiting with an open tray menu retained a popup, icon or blocked AWT thread");
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            org.junit.jupiter.api.Assumptions.assumeFalse(output.contains("TRAY_UNAVAILABLE"), "Windows session has no system tray");
            assertTrue(output.contains("TRAY_POPUP_EXIT_OK"), output);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    /** Uses only this child JVM's icon/listeners/windows; it never sends OS input or opens an account workspace. */
    public static final class OpenTrayPopupExitProbe {
        public static void main(String[] args) throws Exception {
            var platform = new WorkbenchTray.AwtTrayPlatform();
            if (!platform.supported()) { System.out.println("TRAY_UNAVAILABLE"); return; }
            var baselineIcons = java.util.Arrays.asList(java.awt.SystemTray.getSystemTray().getTrayIcons());
            var baselineWindows = java.util.Arrays.asList(java.awt.Window.getWindows());
            var opened = new AtomicInteger(); var exited = new AtomicInteger();
            try {
                platform.install(opened::incrementAndGet, exited::incrementAndGet);
                EventQueue.invokeAndWait(() -> {
                    var added = java.util.Arrays.stream(java.awt.SystemTray.getSystemTray().getTrayIcons()).filter(icon -> !baselineIcons.contains(icon)).toList();
                    if (added.size() != 1 || !platform.active()) throw new AssertionError("The child did not install exactly one owned tray icon");
                    var icon = added.getFirst();
                    if (icon.getMouseListeners().length == 0) throw new AssertionError("The installed tray has no popup listener");
                    // MouseEvent requires a Component source. The unshown Canvas and direct
                    // application-listener call deliberately avoid native mouse injection.
                    var popupTrigger = new MouseEvent(new java.awt.Canvas(), MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                            InputEvent.BUTTON3_DOWN_MASK, 40, 40, 1, true, MouseEvent.BUTTON3);
                    for (var listener : icon.getMouseListeners()) listener.mouseReleased(popupTrigger);
                    var dialogs = java.util.Arrays.stream(java.awt.Window.getWindows()).filter(window -> !baselineWindows.contains(window))
                            .filter(javax.swing.JDialog.class::isInstance).map(javax.swing.JDialog.class::cast).toList();
                    if (dialogs.size() != 1) throw new AssertionError("The registered popup trigger did not create exactly one child dialog");
                    var popup = dialogs.getFirst();
                    if (!popup.isVisible() || !popup.isDisplayable() || popup.isModal()) throw new AssertionError("The owned popup was not open and non-modal");
                    var panel = popup.getContentPane();
                    if (panel.getComponentCount() != 3 || !(panel.getComponent(0) instanceof javax.swing.JButton open)
                            || !(panel.getComponent(1) instanceof javax.swing.JSeparator) || !(panel.getComponent(2) instanceof javax.swing.JButton exit))
                        throw new AssertionError("The installed tray did not open its Swing actions");
                    boolean chinese = WorkbenchTray.AwtTrayPlatform.chineseMenuFont() != null;
                    if (!open.getText().equals(chinese ? "打开资源台" : "Open workbench") || !exit.getText().equals(chinese ? "正常退出" : "Exit")
                            || open.getFont().canDisplayUpTo(open.getText()) != -1 || exit.getFont().canDisplayUpTo(exit.getText()) != -1)
                        throw new AssertionError("The actual popup action text/font differs from the readable menu contract");
                    if (opened.get() != 0 || exited.get() != 0) throw new AssertionError("Opening a popup executed a primary action");
                    // Remove before this EDT callback returns, so focus-lost cannot dispose
                    // it first and hide a missing remove()->closePopup cleanup branch.
                    try { platform.remove(); } catch (Exception failure) { throw new AssertionError(failure); }
                    if (popup.isDisplayable() || popup.isVisible() || platform.active()
                            || java.util.Arrays.asList(java.awt.SystemTray.getSystemTray().getTrayIcons()).contains(icon))
                        throw new AssertionError("Removing the tray left its open popup or owned icon alive");
                });
                var responsive = new AtomicInteger(); EventQueue.invokeAndWait(responsive::incrementAndGet);
                if (responsive.get() != 1) throw new AssertionError("Popup lifecycle blocked the EDT");
                System.out.println("TRAY_POPUP_EXIT_OK");
            } finally {
                platform.remove();
                EventQueue.invokeAndWait(() -> {
                    for (var window : java.awt.Window.getWindows()) if (!baselineWindows.contains(window)) window.dispose();
                });
            }
        }
    }

    private WorkbenchTray tray(Database db, FakeTray platform) { return new WorkbenchTray(db, platform, () -> LAUNCH, () -> {}); }
    private int settingsCount(Database db) {
        return db.read(c -> { try (var r = c.createStatement().executeQuery("SELECT COUNT(*) FROM app_settings")) { r.next(); return r.getInt(1); } });
    }
    private static final class FakeTray implements WorkbenchTray.TrayPlatform {
        boolean supported = true, failInstall, failRemoveOnce; volatile boolean active; int installs;
        Runnable open, exit; volatile URI uri; final CountDownLatch browsed = new CountDownLatch(1);
        public boolean supported() { return supported; }
        public boolean active() { return active; }
        public void install(Runnable open, Runnable exit) {
            installs++; this.open = open; this.exit = exit; active = true;
            if (failInstall) throw new IllegalStateException("synthetic tray failure");
        }
        public void remove() { if (failRemoveOnce) { failRemoveOnce = false; throw new IllegalStateException("synthetic native removal failure"); } active = false; }
        public void browse(URI uri) { this.uri = uri; browsed.countDown(); }
    }
}
