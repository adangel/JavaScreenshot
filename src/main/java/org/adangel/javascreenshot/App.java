package org.adangel.javascreenshot;

import org.adangel.javascreenshot.ui.ImagePainter;
import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    private static final Logger LOG = LoggerFactory.getLogger(App.class);

    private BufferedImage shot;
    private JButton button = new JButton("Take Screenshot");
    private JButton saveButton = new JButton("Save...");
    private ImagePainter painter = new ImagePainter();

    public App() {
        JFrame frame = new JFrame("Java Screenshot");
        frame.setLayout(new BorderLayout());
        frame.add(painter, BorderLayout.CENTER);

        button.addActionListener(e -> createShot());

        JPanel buttons = new JPanel();
        buttons.setLayout(new GridLayout(1, 0));
        buttons.add(button);
        saveButton.setEnabled(false);
        saveButton.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            String part = LocalDateTime.now().withNano(0).toString().replaceAll(":", "-");
            fc.setSelectedFile(new File("screenshot-" + part + ".png"));
            int returnVal = fc.showSaveDialog(frame);
            if (returnVal == JFileChooser.APPROVE_OPTION) {
                try {
                    File file = fc.getSelectedFile();
                    ImageIO.write(shot, "png", file);
                    System.out.printf("Image saved to %s%n", file);
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        });
        buttons.add(saveButton);
        JButton exitButton = new JButton("Exit");
        exitButton.addActionListener(e -> System.exit(0));
        buttons.add(exitButton);
        frame.add(buttons, BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        frame.pack();
        frame.setVisible(true);

        if (SystemTray.isSupported()) {
            SystemTray tray = SystemTray.getSystemTray();

            Icon computerIcon = UIManager.getIcon("FileView.computerIcon");
            BufferedImage image = new BufferedImage(computerIcon.getIconWidth(), computerIcon.getIconHeight(), BufferedImage.TYPE_INT_RGB);
            computerIcon.paintIcon(frame, image.getGraphics(), 0, 0);
            /*
            for (Object key : UIManager.getDefaults().keySet()) {
                if (key.toString().endsWith("Icon"))
                    System.out.println(key);
            }
            System.exit(0);
             */

            PopupMenu popup = new PopupMenu();
            MenuItem exit = new MenuItem("Exit");
            exit.addActionListener(e -> System.exit(0));
            popup.add("Screen Shot");
            popup.addSeparator();
            MenuItem showWindow = new MenuItem("Show/Hide");
            showWindow.addActionListener(e -> frame.setVisible(!frame.isVisible()));
            popup.add(showWindow);
            MenuItem createShot = new MenuItem("Create Shot");
            createShot.addActionListener(e -> createShot());
            popup.add(createShot);
            popup.addSeparator();
            popup.add(exit);

            TrayIcon icon = new TrayIcon(image, "Screen Shot", popup);
            icon.addActionListener(e -> {
                frame.setVisible(!frame.isVisible());
            });
            icon.setImageAutoSize(true);
            try {
                tray.add(icon);
            } catch (AWTException ex) {
                ex.printStackTrace();
            }
        } else {
            frame.setVisible(true);
        }
    }

    private void createShot() {
        button.setEnabled(false);
        Runnable runner = () -> {
            BufferedImage image = ScreenshotUtil.createScreenshot();
            if (image != null) {
                painter.setImage(image);
                shot = image;
                saveButton.setEnabled(true);
            }
            button.setEnabled(true);
        };
        new Thread(runner, "screen-shutter").start();
    }
}
