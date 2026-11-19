/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.legacy;

/** Pure surface boundary for the CAFA v5 compatibility session. */
public interface LegacySurface {
    byte[] snapshot();
    boolean input(int serial, byte[] payload);
    void reset();
}
