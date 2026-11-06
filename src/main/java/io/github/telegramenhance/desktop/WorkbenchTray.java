package io.github.telegramenhance.desktop;

import io.github.telegramenhance.security.LocalAccess;
import io.github.telegramenhance.storage.Database;
import jakarta.annotation.PreDestroy;
import java.awt.AWTError;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Optional desktop entry point; browser windows never own the backend lifecycle. */
@Component
public final class WorkbenchTray implements AutoCloseable {
    private static final String SETTING = "desktop.tray_enabled";
    private static final String UNAVAILABLE = "当前桌面环境不支持系统托盘；请使用启动脚本重新打开资源台。";
    private final Database database;
    private final TrayPlatform platform;
    private final Supplier<URI> launchUri;
    private final Runnable shutdown;
    private final AtomicBoolean exiting = new AtomicBoolean();
    private boolean enabled, ready, closed;
    private String warning = "";

    @Autowired
    public WorkbenchTray(Database database, LocalAccess access, ConfigurableApplicationContext context) {
        this(database, new AwtTrayPlatform(), access::launchUri, context::close);
    }
    WorkbenchTray(Database database, TrayPlatform platform, Supplier<URI> launchUri, Runnable shutdown) {
        this.database = database; this.platform = platform; this.launchUri = launchUri; this.shutdown = shutdown;
        enabled = database.read(c -> {
            try (var q = c.prepareStatement("SELECT setting_value FROM app_settings WHERE setting_key=?")) {
                q.setString(1, SETTING);
                try (var rows = q.executeQuery()) { return rows.next() && "true".equals(rows.getString(1)); }
            }
        });
    }
    public record Update(Boolean trayEnabled) {}
    public record View(boolean trayEnabled, boolean traySupported, boolean trayActive, String warning) {}

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void started() { if (!closed && !exiting.get()) { ready = true; apply(); } }

    @EventListener(ContextClosedEvent.class) @Order(Ordered.HIGHEST_PRECEDENCE)
    public void contextClosing() { close(); }

    public synchronized View view() {
        boolean supported = platform.supported();
        return new View(enabled, supported, !closed && platform.active(), supported ? warning : UNAVAILABLE);
    }

    public synchronized View save(Update input) {
        if (input == null || input.trayEnabled() == null) throw new IllegalArgumentException("请选择是否启用系统托盘");
        if (closed || exiting.get()) throw new IllegalArgumentException("资源台正在退出，请稍后重新启动");
        database.write(c -> {
            try (var q = c.prepareStatement("INSERT INTO app_settings(setting_key,setting_value,updated_at) VALUES(?,?,?)"
                    + " ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value,updated_at=excluded.updated_at")) {
                q.setString(1, SETTING); q.setString(2, input.trayEnabled().toString()); q.setLong(3, System.currentTimeMillis()); q.executeUpdate();
            }
            return null;
        });
        enabled = input.trayEnabled(); apply(); return view();
    }

    private void apply() {
        warning = "";
        try {
            if (!enabled) { platform.remove(); return; }
            if (!platform.supported()) { warning = UNAVAILABLE; return; }
            if (ready && !platform.active()) platform.install(this::open, this::requestExit);
        } catch (Exception | AWTError failure) {
            try { platform.remove(); } catch (Exception | AWTError ignored) { }
            warning = "系统托盘未能启用；后台继续运行，请使用启动脚本重新打开资源台。";
        }
    }

    private void open() {
        // Desktop.browse may block the AWT event thread; a virtual worker cannot keep
        // the process alive after normal shutdown and never logs the access URI.
        Thread.ofVirtual().name("desktop-open").start(() -> {
            synchronized (this) { if (closed || exiting.get()) return; }
            try { platform.browse(launchUri.get()); }
            catch (Exception | AWTError failure) {
                synchronized (this) { warning = "无法打开默认浏览器，请使用启动脚本重新打开资源台。"; }
            }
        });
    }

    /** Both the HTTP endpoint and the tray remove the icon before acknowledging exit. */
    public void requestExit() {
        if (exiting.compareAndSet(false, true)) {
            // HTTP callers remove synchronously. A menu callback must first leave
            // the EDT: an in-flight save may hold our monitor while awaiting AWT.
            boolean removeOnWorker = EventQueue.isDispatchThread();
            if (!removeOnWorker) close();
            Thread.ofPlatform().name("workbench-exit").daemon(false).start(() -> {
                if (removeOnWorker) close();
                // Let the HTTP acknowledgement flush before stopping the web server.
                try { Thread.sleep(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                shutdown.run();
            });
        }
    }

    @PreDestroy @Override
    public synchronized void close() {
        closed = true; exiting.set(true);
        // A failed native removal can be retried by ContextClosedEvent / @PreDestroy.
        try { platform.remove(); } catch (Exception | AWTError ignored) { }
    }

    interface TrayPlatform {
        boolean supported();
        boolean active();
        void install(Runnable open, Runnable exit) throws Exception;
        void remove() throws Exception;
        void browse(URI uri) throws Exception;
    }

    static final class AwtTrayPlatform implements TrayPlatform {
        private volatile TrayIcon icon;
        private JDialog popupWindow;
        @Override public boolean supported() {
            try { return !GraphicsEnvironment.isHeadless() && SystemTray.isSupported(); }
            catch (AWTError | UnsupportedOperationException | SecurityException unavailable) { return false; }
        }
        @Override public boolean active() {
            TrayIcon current = icon;
            try { return current != null && supported() && Arrays.asList(SystemTray.getSystemTray().getTrayIcons()).contains(current); }
            catch (AWTError | UnsupportedOperationException | SecurityException unavailable) { return false; }
        }
        @Override public void install(Runnable open, Runnable exit) throws Exception {
            onAwt(() -> {
                if (active()) return;
                var image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
                var graphics = image.createGraphics();
                try {
                    graphics.setColor(new Color(37, 99, 235)); graphics.fillRoundRect(1, 1, 30, 30, 8, 8);
                    graphics.setColor(Color.WHITE); graphics.fillRect(8, 8, 16, 3); graphics.fillRect(8, 14, 12, 3); graphics.fillRect(8, 20, 16, 3);
                } finally { graphics.dispose(); }
                var next = new TrayIcon(image, "Telegram 资源台"); next.setImageAutoSize(true);
                next.addActionListener(event -> open.run());
                next.addMouseListener(new MouseAdapter() {
                    private void popup(MouseEvent event) {
                        if (!event.isPopupTrigger() || icon == null) return;
                        closePopup();
                        popupWindow = createPopup(event.getX(), event.getY(), open, exit);
                        popupWindow.setVisible(true);
                        popupWindow.getContentPane().getComponent(0).requestFocusInWindow();
                    }
                    @Override public void mousePressed(MouseEvent event) { popup(event); }
                    @Override public void mouseReleased(MouseEvent event) { popup(event); }
                });
                SystemTray.getSystemTray().add(next); icon = next;
            });
        }
        @Override public void remove() throws Exception {
            if (icon == null && popupWindow == null) return;
            onAwt(() -> {
                closePopup();
                TrayIcon current = icon;
                if (current != null) { SystemTray.getSystemTray().remove(current); icon = null; }
            });
        }
        @Override public void browse(URI uri) throws Exception {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                throw new UnsupportedOperationException("browser unavailable");
            Desktop.getDesktop().browse(uri);
        }
        private void closePopup() {
            if (popupWindow != null) { popupWindow.dispose(); popupWindow = null; }
        }
        static JPanel createMenu(Runnable open, Runnable exit) {
            Font chinese = chineseMenuFont();
            Font font = chinese != null ? chinese : new Font("Segoe UI", Font.PLAIN, 13);
            // Swing's Java2D renderer retains CJK fallback with UTF-8. The Windows
            // AWT native menu font descriptors can replace these glyphs with boxes.
            var menu = new JPanel(); menu.setLayout(new BoxLayout(menu, BoxLayout.Y_AXIS)); menu.setFont(font);
            menu.setBackground(Color.WHITE);
            menu.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(203,220,232)), BorderFactory.createEmptyBorder(4,4,4,4)));
            var show = menuButton(chinese != null ? "打开资源台" : "Open workbench", font, open);
            var quit = menuButton(chinese != null ? "正常退出" : "Exit", font, exit);
            menu.add(show);
            var separator = new JSeparator(); separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1)); menu.add(separator);
            menu.add(quit);
            for (var button : new JButton[]{show,quit}) {
                var other = button == show ? quit : show;
                for (String key : new String[]{"UP","DOWN"}) button.getInputMap().put(KeyStroke.getKeyStroke(key), "other-item");
                button.getActionMap().put("other-item", new AbstractAction() { public void actionPerformed(ActionEvent event) { other.requestFocusInWindow(); } });
            }
            return menu;
        }
        private static JButton menuButton(String label, Font font, Runnable action) {
            var button = new JButton(label); button.setFont(font); button.setHorizontalAlignment(SwingConstants.LEADING);
            button.setBackground(Color.WHITE); button.setForeground(new Color(32,52,71)); button.setBorderPainted(false);
            button.setBorder(BorderFactory.createEmptyBorder(8,12,8,16)); button.setAlignmentX(0);
            button.setPreferredSize(new Dimension(174,36)); button.setMaximumSize(new Dimension(Integer.MAX_VALUE,36));
            button.addActionListener(event -> action.run());
            button.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "activate-item");
            button.getActionMap().put("activate-item", new AbstractAction() { public void actionPerformed(ActionEvent event) { button.doClick(); } });
            return button;
        }
        static JDialog createPopup(int x, int y, Runnable open, Runnable exit) {
            var window = new JDialog((Frame)null); window.setUndecorated(true); window.setType(Window.Type.POPUP);
            window.setAlwaysOnTop(true);
            window.setContentPane(createMenu(() -> { window.dispose(); open.run(); }, () -> { window.dispose(); exit.run(); }));
            window.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close-menu");
            window.getRootPane().getActionMap().put("close-menu", new AbstractAction() { public void actionPerformed(ActionEvent event) { window.dispose(); } });
            window.addWindowFocusListener(new WindowAdapter() { @Override public void windowLostFocus(WindowEvent event) { window.dispose(); } });
            window.pack();
            var configuration = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
            for (var device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
                if (device.getDefaultConfiguration().getBounds().contains(x,y)) { configuration = device.getDefaultConfiguration(); break; }
            var insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
            var bounds = new Rectangle(configuration.getBounds()); bounds.x += insets.left; bounds.y += insets.top;
            bounds.width -= insets.left + insets.right; bounds.height -= insets.top + insets.bottom;
            window.setLocation(Math.max(bounds.x, Math.min(x, bounds.x + bounds.width - window.getWidth())), Math.max(bounds.y, Math.min(y, bounds.y + bounds.height - window.getHeight())));
            return window;
        }
        static Font chineseMenuFont() {
            // Prefer the Windows UI face for Java2D menu labels, without relying
            // on the AWT native peer's separate legacy charset configuration.
            var families = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT));
            for (String preferred : new String[]{"Microsoft YaHei UI", "Microsoft YaHei", "SimSun", "Noto Sans CJK SC"}) {
                for (String family : families) if (family.equalsIgnoreCase(preferred)) {
                    Font font = new Font(family, Font.PLAIN, 13);
                    if (font.canDisplayUpTo("打开资源台正常退出") == -1) return font;
                }
            }
            var logical = Set.of(Font.DIALOG, Font.DIALOG_INPUT, Font.SANS_SERIF, Font.SERIF, Font.MONOSPACED);
            for (String family : families) if (!logical.contains(family)) {
                Font font = new Font(family, Font.PLAIN, 13);
                if (font.canDisplayUpTo("打开资源台正常退出") == -1) return font;
            }
            return null;
        }
        private static void onAwt(CheckedAction action) throws Exception {
            if (EventQueue.isDispatchThread()) { action.run(); return; }
            var task = new FutureTask<Void>(() -> { action.run(); return null; });
            EventQueue.invokeAndWait(task); task.get();
        }
        @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
    }
}
