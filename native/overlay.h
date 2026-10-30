// overlay.h — on-screen gamepad, shown whenever no real gamepad is connected.
//
// Which controls appear comes from the manifest `controls` presentation hint
// (WC_CTRL_* mask; none when it is empty). A cart that says nothing gets the whole
// pad: there is no way to tell what it reads, a missing right stick can make a game
// unplayable, and extras are only clutter. The player can switch sticks, stick
// clicks, shoulders and triggers on or off, whatever the cart said; that, size,
// opacity, stick behavior and each control's position are saved per cart.

#ifndef WASMCART_OVERLAY_H
#define WASMCART_OVERLAY_H

#include "wasmcart_host.h"
#include "SDL.h"
#include <stdbool.h>

#define OVERLAY_FULL_PAD (WC_CTRL_DPAD | WC_CTRL_LSTICK | WC_CTRL_RSTICK | WC_CTRL_A | WC_CTRL_B | \
                          WC_CTRL_X | WC_CTRL_Y | WC_CTRL_L | WC_CTRL_R | WC_CTRL_LTRIG | \
                          WC_CTRL_RTRIG | WC_CTRL_START | WC_CTRL_SELECT | WC_CTRL_L3 | WC_CTRL_R3)

// Settings, for overlay_command and overlay_get. PlayerActivity's OV_* match.
enum {
    OV_EDIT = 0,     // 1 enters the layout editor, 0 leaves it and saves
    OV_SIZE,         // every control, percent
    OV_OPACITY,      // percent
    OV_GROUPS,       // OV_GROUP_* switched on
    OV_FLOATING,     // sticks center where the thumb lands
    OV_HAPTICS,
    OV_SHOW,         // 0 hides the controls for this cart
    OV_RESET,        // back to the defaults for this cart
};
enum { OV_GROUP_STICKS = 1, OV_GROUP_CLICKS = 2, OV_GROUP_SHOULDERS = 4, OV_GROUP_TRIGGERS = 8 };

// Create GL resources + haptics and load the saved settings. Call once with the
// GL context current. px_per_mm: physical scale (from display DPI); controls are
// sized in mm, not px.
void overlay_init(uint32_t cart_mask, float px_per_mm, const char* config_path);

// Recompute positions. game_rect_* is the letterboxed cart rect in window
// pixels — dials center in the bezel bars when the bars are wide enough.
void overlay_layout(int win_w, int win_h,
                    float game_x, float game_y, float game_w, float game_h);

// Fade in/out (controller hotplug). Input stops the moment hiding starts.
void overlay_set_visible(bool visible);

// Feed SDL events. Returns true if the event belongs to a control; any other
// touch is the cart's. In the editor every touch belongs to the overlay.
bool overlay_event(const SDL_Event* ev, int win_w, int win_h, double now_ms);

// OR the overlay's current state into a pad (call after poll_pads, before
// wc_host_set_pads). Marks the pad connected while the overlay is visible.
void overlay_apply(wc_pad_t* pad);

// Draw. Call after the cart's frame is on FBO 0, before SDL_GL_SwapWindow.
// save_gl_state: true for GL carts (their state caches must survive us).
void overlay_render(bool save_gl_state, double now_ms);

// From the UI thread: queue a setting, or read one. Applied by overlay_pump on
// the thread that owns GL, which calls it once per loop.
void overlay_command(int key, int value);
int  overlay_get(int key);
void overlay_pump(void);
bool overlay_editing(void);

void overlay_shutdown(void);

#endif // WASMCART_OVERLAY_H
