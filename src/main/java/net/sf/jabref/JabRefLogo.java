/*  Copyright (C) 2003-2015 JabRef contributors.
    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
 */
package net.sf.jabref;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/**
 * Shared Java2D representation of the JabRef logo used by splash 
 * screen and About dialog.
 * The path geometry is copied from the legacy splash.svg artwork.
 */
public final class JabRefLogo {

    public static final Color DEFAULT_COLOR = new Color(61, 61, 95);

    private static final Shape MARK = createMark();

    private JabRefLogo() {
    }

    public static void paint(Graphics2D g, int x, int y, int width, int height) {
        paint(g, x, y, width, height, DEFAULT_COLOR, 1.0f);
    }

    public static void paint(Graphics2D g, int x, int y, int width, int height,
            Color color, float alpha) {
        Composite oldComposite = g.getComposite();
        try {
            g.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, Math.max(0.0f, Math.min(1.0f, alpha))));
            g.setColor(color == null ? DEFAULT_COLOR : color);

            Rectangle2D bounds = MARK.getBounds2D();
            double scale = Math.min(width / bounds.getWidth(), height / bounds.getHeight());
            double renderedWidth = bounds.getWidth() * scale;
            double renderedHeight = bounds.getHeight() * scale;
            double offsetX = x + ((width - renderedWidth) / 2.0);
            double offsetY = y + ((height - renderedHeight) / 2.0);

            AffineTransform transform = new AffineTransform();
            transform.translate(offsetX, offsetY);
            transform.scale(scale, scale);
            transform.translate(-bounds.getX(), -bounds.getY());
            g.fill(transform.createTransformedShape(MARK));
        } finally {
            g.setComposite(oldComposite);
        }
    }

    private static Shape createMark() {
        Path2D.Double mark = new Path2D.Double(Path2D.WIND_NON_ZERO);

        // Left path (SVG path3874).
        mark.moveTo(157.96931, 200.83693);
        mark.curveTo(195.68133, 200.83693, 226.84892, 201.74266, 226.84892, 201.74266);
        mark.curveTo(226.84892, 201.74266, 264.67261, 230.02095, 245.09466, 392.86816);
        mark.curveTo(239.45611, 424.41127, 211.69305, 452.41514, 177.77033, 452.41514);
        mark.curveTo(143.84738, 452.41514, 124.35978, 432.41027, 124.35978, 409.07782);
        mark.curveTo(159.37053, 424.87768, 185.33022, 416.88114, 194.38104, 413.14131);
        mark.curveTo(198.72239, 411.34830, 214.85378, 403.80956, 214.58004, 365.73926);
        mark.curveTo(213.94457, 277.38853, 222.57808, 258.19710, 157.96931, 200.83693);
        mark.closePath();

        // Right path (SVG path3876).
        mark.moveTo(255.00983, 201.14950);
        mark.curveTo(346.39949, 205.65848, 415.05774, 279.60734, 324.95506, 328.62763);
        mark.curveTo(299.66930, 344.58746, 362.34330, 423.95718, 386.64022, 459.88743);
        mark.curveTo(324.57976, 439.24633, 270.32186, 376.11080, 274.24748, 333.19936);
        mark.curveTo(276.87135, 302.14849, 327.63081, 304.24915, 329.72814, 285.14140);
        mark.curveTo(331.82536, 266.03489, 296.37990, 268.06417, 255.00983, 201.14950);
        mark.closePath();

        return mark;
    }
}
