package org.adangel.javascreenshot.ui;

import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;

public class ImagePainter extends Canvas {
    
    private BufferedImage image;
    private final Dimension preferredSize = new Dimension();

    public ImagePainter() {
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        preferredSize.setSize(screenSize.width * .2, screenSize.height * .2);
    }

    public void setImage(BufferedImage image) {
        this.image = image;
        repaint();
    }

    @Override
    public void paint(Graphics g) {
        if (image != null) {
            g.drawImage(image, 0, 0, this.getWidth(), this.getHeight(), null);
        }
    }

    @Override
    public Dimension getPreferredSize() {
        return preferredSize;
    }
}
