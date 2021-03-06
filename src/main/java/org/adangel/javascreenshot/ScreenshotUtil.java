package org.adangel.javascreenshot;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TransferQueue;
import javax.imageio.ImageIO;
import org.adangel.javascreenshot.dbus.ScreenshotInterface;
import org.freedesktop.dbus.DBusMap;
import org.freedesktop.dbus.DBusMatchRule;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.interfaces.DBusSigHandler;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScreenshotUtil {

    private static final Logger LOG = LoggerFactory.getLogger(ScreenshotUtil.class);

    public static BufferedImage createScreenshot() {
        if (isWayland()) {
            LOG.debug("Wayland detected");
            return createDbusScreenshot();
        }
        return createRobotScreenshot();
    }

    private static boolean isWayland() {
        return "wayland".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE"));
    }

    private static BufferedImage createRobotScreenshot() {
        try {
            LOG.debug("Taking robot screenshot");
            Robot robot = new Robot();
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            Rectangle screenRect = new Rectangle(screenSize);
            BufferedImage shot = robot.createScreenCapture(screenRect);
            return shot;
        } catch (Exception ex) {
            LOG.error("Error creating robot screenshot", ex);
            return null;
        }
    }

    private static BufferedImage createDbusScreenshot() {
        try {
            LOG.debug("Taking DBus Screenshot...");
            DBusConnection bus = DBusConnection.getConnection(DBusConnection.DBusBusType.SESSION);
            System.out.printf("Unique name: %s%n", bus.getUniqueName());

            String token = UUID.randomUUID().toString().replaceAll("-", "");
            String sender = bus.getUniqueName().substring(1).replace('.', '_');
            String path = String.format("/org/freedesktop/portal/desktop/request/%s/%s", sender, token);

            TransferQueue<Optional<BufferedImage>> queue = new LinkedTransferQueue<>();

            DBusMatchRule matchRule = new DBusMatchRule("signal", "org.freedesktop.portal.Request", "Response");
            bus.addGenericSigHandler(matchRule, new DBusSigHandler<DBusSignal>() {
                @Override
                public void handle(DBusSignal t) {
                    System.out.println("--- signal received ---");
                    System.out.println(t);
                    if (path.equals(t.getPath())) {
                        try {
                            Object[] params = t.getParameters();
                            System.out.printf("params: size=%d%n", params.length);
                            UInt32 response = (UInt32) params[0];
                            DBusMap<String, Variant> results = (DBusMap) params[1];

                            if (response.intValue() == 0) {
                                System.out.println("Screenshot successfull");
                                Variant vuri = results.get("uri");
                                String uri = (String) vuri.getValue();
                                System.out.printf("uri: %s%n", uri);

                                BufferedImage shot = ImageIO.read(URI.create(uri).toURL());
                                queue.add(Optional.of(shot));

                                if (Files.deleteIfExists(Path.of(URI.create(uri)))) {
                                    System.out.println("deleted...");
                                }
                            } else {
                                System.out.printf("Failed: response=%s%n", response);
                                queue.add(Optional.empty());
                            }

                            bus.removeGenericSigHandler(matchRule, this);
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
            });

            ScreenshotInterface iface = bus.getRemoteObject("org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop", ScreenshotInterface.class);
            Map<String, Variant> options = new HashMap<>();
            options.put("interactive", new Variant(Boolean.FALSE));
            options.put("handle_token", new Variant(token));
            DBusPath result = iface.Screenshot("", options);
            System.out.printf("       result: %s%n", result);
            System.out.printf("expected path: %s%n", path);

            Optional<BufferedImage> shotResult = queue.take();
            if (shotResult.isPresent()) {
                return shotResult.get();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
