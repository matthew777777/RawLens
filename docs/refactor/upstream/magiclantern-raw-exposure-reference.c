/** \file
 * Big file that needs to be splitted by features
 *
 */
/*
 * Copyright (C) 2009 Trammell Hudson <hudson+ml@osresearch.net>
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the
 * Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor,
 * Boston, MA  02110-1301, USA.
 */
/* Pinned source reference; not compiled. Original line ranges below. */
// src/zebra.c:923-940
static int zebra_rgb_solid_color(int underexposed, int clipR, int clipG, int clipB)
{
    if (underexposed) return ZEBRA_COLOR_WORD_SOLID(79);
    
    switch ((clipR ? 4 : 0) |
            (clipG ? 2 : 0) |
            (clipB ? 1 : 0))
    {
        case 0b111: return ZEBRA_COLOR_WORD_SOLID(COLOR_BLACK);
        case 0b110: return ZEBRA_COLOR_WORD_SOLID(COLOR_YELLOW);
        case 0b101: return ZEBRA_COLOR_WORD_SOLID(COLOR_MAGENTA);
        case 0b011: return ZEBRA_COLOR_WORD_SOLID(COLOR_CYAN);
        case 0b100: return ZEBRA_COLOR_WORD_SOLID(COLOR_RED);
        case 0b001: return ZEBRA_COLOR_WORD_SOLID(COLOR_BLUE);
        case 0b010: return ZEBRA_COLOR_WORD_SOLID(COLOR_GREEN2);
        default: return 0;
    }
}
// src/zebra.c:2452-2484
    #ifdef FEATURE_RAW_SPOTMETER
    int raw_luma = 0;
    int raw_ev = 0;
    if (can_use_raw_overlays() && raw_update_params())
    {
        const int xcr = BM2RAW_X(xcb);
        const int ycr = BM2RAW_Y(ycb);
        const int dxr = BM2RAW_DX(dxb);

        raw_luma = 0;
        int raw_count = 0;
        for( y = ycr - dxr ; y <= ycr + dxr ; y++ )
        {
            if (y < raw_info.active_area.y1 || y > raw_info.active_area.y2) continue;
            for( x = xcr - dxr ; x <= xcr + dxr ; x++ )
            {
                if (x < raw_info.active_area.x1 || x > raw_info.active_area.x2) continue;

                raw_luma += raw_get_pixel(x, y);
                raw_count++;
                
                /* define this to check if spotmeter reads from the right place;
                 * you should see some gibberish on raw zebras, right inside the spotmeter box */
                #ifdef RAW_SPOTMETER_TEST
                raw_set_pixel(raw_buf, x, y, rand());
                #endif
            }
        }
        if (!raw_count) return;
        raw_luma /= raw_count;
        raw_ev = (int) roundf(10.0 * raw_to_ev(raw_luma));
    }
    #endif
// src/raw.c:1769-1813
/* input: 0 - 16384 (valid range: from black level to white level) */
/* output: -14 ... 0 */
float FAST raw_to_ev(int raw)
{
    int raw_max = raw_info.white_level - raw_info.black_level;

    if (unlikely(raw_info.white_level > 16383) && unlikely(raw > 10000))
    {
        /**
         * Hack for photo mode LV raw overlays (histogram & friends)
         * to show correct overexposure warnings when ExpSim is done with -1/3 EV digital ISO.
         *
         * Canon implements ExpSim by varying iso/shutter/aperture in full stops, and digital ISO for 1/3 stops.
         * Digital ISO does not affect the raw histogram, so they add -1/3, 0 or +1/3 EV when developing the raw for LV display
         * We did the same adjustment by adjusting the white level in raw_update_params_once.
         * But when the correction is -1/3 EV, the white level is greater than 16383,
         * so the overexposure indicators will read a negative EV instead of 0 (they will no longer indicate overexposure).
         *
         * With this hack, we are pushing raw values greater than 10000 towards 0 EV (overexposed) level,
         * thus keeping the correct horizontal position of the histogram at midtones (raw - 1/3 EV)
         * and getting correct overexposure indicators for highlights (0 EV).
         *
         * Math:
         *      at raw=10000 we keep the original white level,
         *      at raw=15000 or more, white level becomes 15000,
         *      with linear interpolation, thus stretching the histogram in the brightest half-stop.
         *
         * Feel free to optimize it with fixed point.
         *
         * This hack has no effect in movie mode or outside LV, because white level is normally under 16383.
         */
        float k = COERCE((raw - 10000) / 5000.0, 0.0, 1.0);
        int adjusted_white = raw_info.white_level * (1-k) + 15000 * k;
        raw_max = adjusted_white - raw_info.black_level;
    }

    float raw_ev = -log2f(raw_max) + log2f(COERCE(raw - raw_info.black_level, 1, raw_max));
    return raw_ev;
}

int FAST ev_to_raw(float ev)
{
    int raw_max = MIN(raw_info.white_level, 16383) - raw_info.black_level;
    return raw_info.black_level + powf(2, ev) * raw_max;
}

