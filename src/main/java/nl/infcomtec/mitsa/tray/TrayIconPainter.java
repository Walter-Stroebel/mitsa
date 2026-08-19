/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.mitsa.tray;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;

/**
 * Loads MITSA's multi-resolution tray artwork and paints it tinted for a
 * status color, scaled to the tray's requested icon size. Ported from
 * ApotheekBezorgtTray.paintIcon, generalized off that app's hardcoded
 * single icon set.
 */
public class TrayIconPainter {

    private BufferedImage icon16;
    private BufferedImage icon32;
    private BufferedImage icon64;
    private BufferedImage icon256;

    public TrayIconPainter() throws IOException {
        icon16 = load("icon16.png");
        icon32 = load("icon32.png");
        icon64 = load("icon64.png");
        icon256 = load("icon256.png");
    }

    private BufferedImage load(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/nl/infcomtec/mitsa/artwork/" + name)) {
            if (in == null) {
                throw new IOException("Missing tray artwork resource: " + name);
            }
            return ImageIO.read(in);
        }
    }

    public Image paint(Dimension trayIconSize, Color tint) {
        BufferedImage source;
        if (trayIconSize.width > 64) {
            source = icon256;
        } else if (trayIconSize.width > 32) {
            source = icon64;
        } else if (trayIconSize.width > 16) {
            source = icon32;
        } else {
            source = icon16;
        }
        BufferedImage bi = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        for (int x = 0; x < source.getWidth(); x++) {
            for (int y = 0; y < source.getHeight(); y++) {
                if (0 == (bi.getRGB(x, y) & 0xFF000000)) {
                    bi.setRGB(x, y, tint.getRGB());
                }
            }
        }
        ImageIcon ii = new ImageIcon(bi.getScaledInstance(trayIconSize.width, trayIconSize.height, Image.SCALE_SMOOTH));
        return ii.getImage();
    }
}
