// overlay.c — on-screen gamepad: SDF-drawn, mm-sized, player-configurable.
//
// Semantics adopted from RetroArch's overlay tuning: hitboxes bigger than visuals
// (reach), hitboxes that grow while held (range_mod), an eightway dpad area
// resolved by angle so directions glide without lifting, and roll-off between
// face buttons (PPSSPP's gliding). Placement follows Microsoft's touch guide
// (from 200+ Xbox titles): one control under each thumb, primary actions on
// arcs around the grip, rare ones top and bottom center, and a single left
// control. A cart that declares both dpad and left stick gets one stick that also
// drives the dpad, so movement and menus share a thumb position. Everything is
// sized in millimeters; pixel-based layouts are the root of RetroArch's Android
// misalignment bugs.

#include "overlay.h"
#include "wc_log.h"
#include <GLES3/gl3.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

// ─── Tunables (mm unless noted) ─────────────────────────────────────────────

#define DIAL_R_MM        11.0f  // dpad/stick radius
#define FACE_R_MM         5.5f  // face button radius
#define FACE_RING_MM      8.5f  // diamond ring radius (adjacent buttons just clear)
#define SHOULDER_W_MM    13.0f
#define SHOULDER_H_MM     6.5f
#define PILL_W_MM         9.0f
#define PILL_H_MM         4.5f
#define THUMB_CLICK_R_MM  4.5f  // L3/R3
#define EDGE_INSET_MM     5.0f  // keeps clear of display cutouts in landscape
#define BOTTOM_RAISE_MM   4.0f  // thumbs rest above the bottom edge
#define STICK_THROW_MM    9.0f
#define STICK_SAT         0.80f // saturate at 80% throw (PPSSPP convention)
#define STICK_DEADZONE    0.05f // radial, of full throw (Xbox touch layouts' value)
#define STICK_GRAB        1.40f // a stick's touch area, over its visual
#define STICK_DPAD_AT     0.45f // shared stick: throw that presses a dpad direction

#define REACH             1.30f // hitbox = visual * reach (keeps pills over 9 mm)
#define RANGE_MOD         1.50f // hitbox growth while pressed
#define DIAG_WINDOW_DEG  40.0f  // diagonal sector width (cardinals get the rest)
#define DPAD_DEADZONE     0.14f // fraction of dial radius
#define DIM_FACTOR        0.45f // idle opacity, of the player's opacity
#define IDLE_DIM_MS    8000.0
#define FADE_MS         200.0
#define DIM_FADE_MS     300.0

// ─── Controls ───────────────────────────────────────────────────────────────

enum { K_DPAD, K_STICK, K_BUTTON, K_RECT };  // K_RECT: shoulders/triggers/pills

// Stable ids: saved settings refer to these.
enum {
    ID_LEFT = 0, ID_RSTICK, ID_A, ID_B, ID_X, ID_Y, ID_L, ID_R, ID_LT, ID_RT,
    ID_START, ID_SELECT, ID_L3, ID_R3, ID_COUNT
};

typedef struct {
    int      id;
    int      kind;
    uint32_t bits;        // WC_BUTTON_* set while pressed (buttons/rects)
    uint8_t  trigger;     // 1 = left trigger, 2 = right (writes an
                          // analog value, not a button bit)
    int      stick;       // K_STICK: 0 left, 1 right
    bool     drives_dpad; // K_STICK: the shared left control
    float    cx, cy;      // center, window px
    float    hx, hy;      // half extents, px (hx==hy==radius for round kinds)
    int      corner;      // rect hit area extends into this screen corner:
                          // 0 none, 1 top-left, 2 top-right
    const char* label;
    float    r, g, b;
    bool     pressed;
    float    press_anim;  // 0..1 smoothed
} ctl_t;

static ctl_t ctls[ID_COUNT];
static int   n_ctls = 0;

typedef struct {
    SDL_FingerID id;
    bool  used;
    int   ctl;            // index into ctls
    float ox, oy;         // stick: origin (window px); editor: grab offset
} touch_t;
static touch_t touches[10];

// Settings
typedef struct {
    bool  moved;
    float fx, fy;         // center, fraction of the window
} ctl_cfg_t;

static struct {
    int  size, opacity;
    int  groups;          // OV_GROUP_* on; -1 = as the cart says
    bool floating, haptics, show;
    ctl_cfg_t ctl[2][ID_COUNT]; // [0] wide, [1] upright: positions don't carry over
} cfg;

static char     g_config_path[4096];
static uint32_t g_cart_mask = 0;
static uint32_t g_mask = 0;
static float    g_ppmm = 16.5f;      // ~420dpi fallback
static int      g_win_w = 0, g_win_h = 0;
static float    g_game_x = 0, g_game_y = 0, g_game_w = 0, g_game_h = 0;

// dpad/stick live state
static uint32_t dpad_bits = 0;
static float    stick_x[2] = {0}, stick_y[2] = {0}; // -1..1
static float    stick_ox[2], stick_oy[2];           // floating base, while held
static bool     stick_held[2] = {0};

// visibility + fades
static bool   g_visible = false;
static bool   g_editing = false;
static int    g_selected = -1;       // id
static float  g_fade = 0.0f;         // 0..1
static float  g_dim = 1.0f;          // idle dim factor
static double g_last_touch_ms = -1.0;
static double g_last_render_ms = 0.0;

// GL
static GLuint prog = 0, vao = 0, font_tex = 0;
static GLint  u_screen, u_center, u_half, u_shape, u_color, u_dirbits, u_param, u_uv, u_font;
enum { SH_CIRCLE = 0, SH_DPAD = 1, SH_RRECT = 2, SH_RING = 3, SH_GLYPH = 4 };

// haptics
static SDL_Haptic* haptic = NULL;

// Commands from the UI thread
static SDL_mutex* cmd_lock = NULL;
static struct { int key, value; } cmd_queue[32];
static int cmd_count = 0;

// ─── Label font: 5x7, only the letters the controls use ─────────────────────

static const char FONT_CHARS[] = "ABELRSTXY3";
static const uint8_t FONT[][7] = {
    {0x0E,0x11,0x11,0x1F,0x11,0x11,0x11}, // A
    {0x1E,0x11,0x11,0x1E,0x11,0x11,0x1E}, // B
    {0x1F,0x10,0x10,0x1E,0x10,0x10,0x1F}, // E
    {0x10,0x10,0x10,0x10,0x10,0x10,0x1F}, // L
    {0x1E,0x11,0x11,0x1E,0x14,0x12,0x11}, // R
    {0x0F,0x10,0x10,0x0E,0x01,0x01,0x1E}, // S
    {0x1F,0x04,0x04,0x04,0x04,0x04,0x04}, // T
    {0x11,0x11,0x0A,0x04,0x0A,0x11,0x11}, // X
    {0x11,0x11,0x0A,0x04,0x04,0x04,0x04}, // Y
    {0x1E,0x01,0x01,0x0E,0x01,0x01,0x1E}, // 3
};
#define FONT_N (sizeof(FONT) / sizeof(FONT[0]))
#define FONT_CELL 6                   // 5 wide + 1 blank so filtering can't bleed

// ─── Haptics ────────────────────────────────────────────────────────────────

static void haptic_init(void) {
    if (SDL_InitSubSystem(SDL_INIT_HAPTIC) != 0) return;
    // SDL's Android backend registers the system vibrator as a haptic device
    if (SDL_NumHaptics() > 0) {
        haptic = SDL_HapticOpen(0);
        if (haptic && SDL_HapticRumbleInit(haptic) != 0) {
            SDL_HapticClose(haptic);
            haptic = NULL;
        }
    }
}

static void haptic_pulse(float strength, uint32_t ms) {
    if (haptic && cfg.haptics) SDL_HapticRumblePlay(haptic, strength, ms);
}

// ─── Settings ───────────────────────────────────────────────────────────────

static void cfg_defaults(void) {
    memset(&cfg, 0, sizeof(cfg));
    cfg.size = 100;
    cfg.opacity = 30;
    cfg.floating = true;
    cfg.haptics = true;
    cfg.show = true;
    cfg.groups = -1;
}

static int clampi(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

static void cfg_load(void) {
    cfg_defaults();
    FILE* f = fopen(g_config_path, "r");
    if (!f) return;
    char line[128];
    while (fgets(line, sizeof(line), f)) {
        int v, o, id, moved;
        float fx, fy;
        if (sscanf(line, "size %d", &v) == 1) cfg.size = clampi(v, 50, 200);
        else if (sscanf(line, "opacity %d", &v) == 1) cfg.opacity = clampi(v, 5, 100);
        else if (sscanf(line, "groups %d", &v) == 1) cfg.groups = clampi(v, -1, 15);
        else if (sscanf(line, "floating %d", &v) == 1) cfg.floating = v != 0;
        else if (sscanf(line, "haptics %d", &v) == 1) cfg.haptics = v != 0;
        else if (sscanf(line, "show %d", &v) == 1) cfg.show = v != 0;
        else if (sscanf(line, "ctl %d %d %d %f %f", &o, &id, &moved, &fx, &fy) == 5 &&
                 o >= 0 && o < 2 && id >= 0 && id < ID_COUNT) {
            cfg.ctl[o][id] = (ctl_cfg_t){ moved != 0, fx, fy };
        }
    }
    fclose(f);
}

static void cfg_save(void) {
    if (!g_config_path[0]) return;
    char tmp[4200];
    snprintf(tmp, sizeof(tmp), "%s.tmp", g_config_path);
    FILE* f = fopen(tmp, "w");
    if (!f) { wc_log_err("overlay: cannot write %s\n", tmp); return; }
    fprintf(f, "size %d\nopacity %d\ngroups %d\nfloating %d\nhaptics %d\nshow %d\n",
            cfg.size, cfg.opacity, cfg.groups, cfg.floating, cfg.haptics, cfg.show);
    for (int o = 0; o < 2; o++) {
        for (int i = 0; i < ID_COUNT; i++) {
            const ctl_cfg_t* c = &cfg.ctl[o][i];
            if (c->moved)
                fprintf(f, "ctl %d %d %d %.5f %.5f\n", o, i, c->moved, c->fx, c->fy);
        }
    }
    fclose(f);
    if (rename(tmp, g_config_path) != 0) wc_log_err("overlay: cannot save %s\n", g_config_path);
}

#define MASK_STICKS    (WC_CTRL_LSTICK | WC_CTRL_RSTICK)
#define MASK_CLICKS    (WC_CTRL_L3 | WC_CTRL_R3)
#define MASK_SHOULDERS (WC_CTRL_L | WC_CTRL_R)
#define MASK_TRIGGERS  (WC_CTRL_LTRIG | WC_CTRL_RTRIG)

// The cart's own groups, until the player has changed any
static int cart_groups(void) {
    return ((g_cart_mask & MASK_STICKS) ? OV_GROUP_STICKS : 0) |
           ((g_cart_mask & MASK_CLICKS) ? OV_GROUP_CLICKS : 0) |
           ((g_cart_mask & MASK_SHOULDERS) ? OV_GROUP_SHOULDERS : 0) |
           ((g_cart_mask & MASK_TRIGGERS) ? OV_GROUP_TRIGGERS : 0);
}

static uint32_t effective_mask(void) {
    if (cfg.groups < 0) return g_cart_mask;
    uint32_t m = g_cart_mask & ~(uint32_t)(MASK_STICKS | MASK_CLICKS | MASK_SHOULDERS | MASK_TRIGGERS);
    if (cfg.groups & OV_GROUP_STICKS) m |= MASK_STICKS;
    // The left thumb still needs something for a cart built around a stick
    else if (g_cart_mask & MASK_STICKS) m |= WC_CTRL_DPAD;
    if ((cfg.groups & OV_GROUP_CLICKS) && (cfg.groups & OV_GROUP_STICKS)) m |= MASK_CLICKS;
    if (cfg.groups & OV_GROUP_SHOULDERS) m |= MASK_SHOULDERS;
    if (cfg.groups & OV_GROUP_TRIGGERS) m |= MASK_TRIGGERS;
    return m;
}

// ─── Layout ────────────────────────────────────────────────────────────────

// Which set of saved positions applies
#define CTL_CFG(id) cfg.ctl[g_win_h > g_win_w ? 1 : 0][id]

static float mm(float v) { return v * g_ppmm * (float)cfg.size / 100.0f; }

static ctl_t* add_ctl(int id, int kind, uint32_t bits, float cx, float cy,
                      float hx, float hy, const char* label, float r, float g, float b) {
    if (n_ctls >= ID_COUNT) return NULL;
    const ctl_cfg_t* c = &CTL_CFG(id);
    ctl_t* out = &ctls[n_ctls++];
    memset(out, 0, sizeof(*out));
    out->id = id; out->kind = kind; out->bits = bits;
    out->hx = hx; out->hy = hy;
    out->cx = c->moved ? c->fx * (float)g_win_w : cx;
    out->cy = c->moved ? c->fy * (float)g_win_h : cy;
    out->label = label;
    out->r = r; out->g = g; out->b = b;
    return out;
}

void overlay_layout(int win_w, int win_h,
                    float game_x, float game_y, float game_w, float game_h) {
    g_win_w = win_w; g_win_h = win_h;
    g_game_x = game_x; g_game_y = game_y; g_game_w = game_w; g_game_h = game_h;

    n_ctls = 0;
    memset(touches, 0, sizeof(touches));
    dpad_bits = 0;
    stick_x[0] = stick_y[0] = stick_x[1] = stick_y[1] = 0.0f;
    stick_held[0] = stick_held[1] = false;
    g_mask = effective_mask();

    float inset = mm(EDGE_INSET_MM);
    float bar_l = game_x;                          // left bezel bar width
    float bar_r = win_w - (game_x + game_w);       // right bar width
    const float grey = 0.85f;

    int face_n = !!(g_mask & WC_CTRL_A) + !!(g_mask & WC_CTRL_B) +
                 !!(g_mask & WC_CTRL_X) + !!(g_mask & WC_CTRL_Y);
    float dial_r = mm(DIAL_R_MM);
    float ring   = mm(FACE_RING_MM);
    float br     = mm(FACE_R_MM);
    float low_y  = win_h - inset - mm(BOTTOM_RAISE_MM);
    float dial_y = low_y - dial_r;
    // Upright, with room below the picture: the controls have that space to
    // themselves, and it may be too narrow for a right stick beside the buttons.
    float game_bottom = game_y + game_h;
    bool  below = win_h > win_w && (float)win_h - game_bottom > dial_r * 2.0f;
    bool  stack_right = below && (g_mask & WC_CTRL_RSTICK) != 0 && face_n > 0;

    // Left thumb: one control. A bar wide enough to hold it holds it, so a
    // 4:3-ish cart on a tall phone has no game pixels covered.
    float left_cx = fmaxf(bar_l * 0.5f, inset + dial_r);
    bool has_left = (g_mask & (WC_CTRL_DPAD | WC_CTRL_LSTICK)) != 0;
    if (has_left) {
        bool is_stick = (g_mask & WC_CTRL_LSTICK) != 0;
        ctl_t* c = add_ctl(ID_LEFT, is_stick ? K_STICK : K_DPAD, 0, left_cx, dial_y,
                           dial_r, dial_r, NULL, grey, grey, grey);
        if (c && is_stick) { c->stick = 0; c->drives_dpad = (g_mask & WC_CTRL_DPAD) != 0; }
    }

    // Right thumb: face buttons at their Xbox diamond anchors (A bottom,
    // B right, X left, Y top) so muscle memory holds for any subset. A right
    // stick sits inboard, within the same thumb's arc.
    float face_cx = win_w - fmaxf(bar_r * 0.5f, inset + ring + br);
    float face_cy = stack_right ? dial_y - dial_r - mm(2.0f) - ring - br : low_y - ring - br;
    if (face_n > 0) {
        if (g_mask & WC_CTRL_A)
            add_ctl(ID_A, K_BUTTON, WC_BUTTON_A, face_cx, face_cy + ring, br, br, "A", 0.30f, 0.69f, 0.31f);
        if (g_mask & WC_CTRL_B)
            add_ctl(ID_B, K_BUTTON, WC_BUTTON_B, face_cx + ring, face_cy, br, br, "B", 0.90f, 0.22f, 0.21f);
        if (g_mask & WC_CTRL_X)
            add_ctl(ID_X, K_BUTTON, WC_BUTTON_X, face_cx - ring, face_cy, br, br, "X", 0.26f, 0.45f, 0.91f);
        if (g_mask & WC_CTRL_Y)
            add_ctl(ID_Y, K_BUTTON, WC_BUTTON_Y, face_cx, face_cy - ring, br, br, "Y", 0.95f, 0.77f, 0.06f);
    }
    float rstick_cx = (face_n == 0 || stack_right) ? win_w - inset - dial_r
                                                   : face_cx - ring - br - dial_r - mm(4.0f);
    if (g_mask & WC_CTRL_RSTICK) {
        ctl_t* c = add_ctl(ID_RSTICK, K_STICK, 0, rstick_cx, dial_y, dial_r, dial_r, NULL, grey, grey, grey);
        if (c) c->stick = 1;
    }

    // Stick clicks: just above their stick, toward the middle of the screen
    float tc = mm(THUMB_CLICK_R_MM);
    if ((g_mask & WC_CTRL_L3) && has_left)
        add_ctl(ID_L3, K_BUTTON, WC_BUTTON_L3, left_cx + dial_r * 0.8f, dial_y - dial_r - tc * 0.8f, tc, tc, "L3", grey, grey, grey);
    if (g_mask & WC_CTRL_R3)
        add_ctl(ID_R3, K_BUTTON, WC_BUTTON_R3, rstick_cx - dial_r * (stack_right ? 1.2f : 0.8f),
                dial_y - dial_r - tc * 0.8f, tc, tc, "R3", grey, grey, grey);

    // Shoulders: top corners, hitboxes reaching into the very corner
    // (upright: a row just above the thumb clusters, not across the picture)
    float sw = mm(SHOULDER_W_MM) * 0.5f, sh = mm(SHOULDER_H_MM) * 0.5f;
    bool  has_trig = (g_mask & (WC_CTRL_LTRIG | WC_CTRL_RTRIG)) != 0;
    float shoulder_y = inset + sh;
    if (below) {
        float cluster_top = fminf(dial_y - dial_r - tc * 2.0f, face_n > 0 ? face_cy - ring - br : dial_y - dial_r);
        shoulder_y = cluster_top - mm(3.0f) - sh - (has_trig ? sh * 2.0f + mm(2.0f) : 0.0f);
        shoulder_y = fmaxf(shoulder_y, game_bottom + mm(2.0f) + sh);
    }
    if (g_mask & WC_CTRL_L) {
        ctl_t* c = add_ctl(ID_L, K_RECT, WC_BUTTON_L, inset + sw, shoulder_y, sw, sh, "L", grey, grey, grey);
        if (c && !below && !CTL_CFG(ID_L).moved) c->corner = 1;
    }
    if (g_mask & WC_CTRL_R) {
        ctl_t* c = add_ctl(ID_R, K_RECT, WC_BUTTON_R, win_w - inset - sw, shoulder_y, sw, sh, "R", grey, grey, grey);
        if (c && !below && !CTL_CFG(ID_R).moved) c->corner = 2;
    }

    // Triggers: below the shoulders, binary 0/WC_TRIGGER_MAX (screens have no pressure)
    float trig_y = shoulder_y + sh * 2.0f + mm(2.0f);
    if (g_mask & WC_CTRL_LTRIG) {
        ctl_t* c = add_ctl(ID_LT, K_RECT, 0, inset + sw, trig_y, sw, sh, "LT", grey, grey, grey);
        if (c) c->trigger = 1;
    }
    if (g_mask & WC_CTRL_RTRIG) {
        ctl_t* c = add_ctl(ID_RT, K_RECT, 0, win_w - inset - sw, trig_y, sw, sh, "RT", grey, grey, grey);
        if (c) c->trigger = 2;
    }

    // Start/Select: pills at bottom-center (sticky immersive means taps here
    // don't fight the home gesture, which needs a swipe)
    float pw = mm(PILL_W_MM) * 0.5f, ph = mm(PILL_H_MM) * 0.5f;
    float pill_y = win_h - inset * 0.6f - ph;
    bool sel = (g_mask & WC_CTRL_SELECT) != 0, sta = (g_mask & WC_CTRL_START) != 0;
    if (sel && sta) {
        add_ctl(ID_SELECT, K_RECT, WC_BUTTON_SELECT, win_w * 0.5f - pw - mm(2.0f), pill_y, pw, ph, "SEL", grey, grey, grey);
        add_ctl(ID_START, K_RECT, WC_BUTTON_START, win_w * 0.5f + pw + mm(2.0f), pill_y, pw, ph, "STA", grey, grey, grey);
    } else if (sel || sta) {
        add_ctl(sta ? ID_START : ID_SELECT, K_RECT, sta ? WC_BUTTON_START : WC_BUTTON_SELECT,
                win_w * 0.5f, pill_y, pw, ph, sta ? "STA" : "SEL", grey, grey, grey);
    }
}

static void relayout(void) {
    overlay_layout(g_win_w, g_win_h, g_game_x, g_game_y, g_game_w, g_game_h);
}

// ─── Hit testing ────────────────────────────────────────────────────────────

static bool ctl_hit(const ctl_t* c, float px, float py) {
    float m = REACH * (c->pressed ? RANGE_MOD : 1.0f);
    if (c->kind == K_RECT) {
        // shoulders own their whole screen corner (reach_up/left semantics)
        float x0 = c->cx - c->hx * m, x1 = c->cx + c->hx * m;
        float y0 = c->cy - c->hy * m, y1 = c->cy + c->hy * m;
        if (c->corner == 1) { x0 = 0; y0 = 0; }
        if (c->corner == 2) { x1 = (float)g_win_w; y0 = 0; }
        return px >= x0 && px <= x1 && py >= y0 && py <= y1;
    }
    if (c->kind == K_STICK) m = STICK_GRAB;
    float dx = (px - c->cx) / (c->hx * m);
    float dy = (py - c->cy) / (c->hy * m);
    return dx * dx + dy * dy <= 1.0f;
}

// Reach-grown hitboxes overlap (close face buttons especially), so a touch
// belongs to the NEAREST control that contains it, not the first in the array.
static int find_ctl(float px, float py) {
    int best = -1;
    float best_d = 1e30f;
    for (int i = 0; i < n_ctls; i++) {
        if (!ctl_hit(&ctls[i], px, py)) continue;
        float dx = px - ctls[i].cx, dy = py - ctls[i].cy;
        float d = dx * dx + dy * dy;
        if (d < best_d) { best_d = d; best = i; }
    }
    return best;
}

// Roll-off target: nearest face button containing the point at BASE reach
// (ignoring range_mod growth — otherwise a grown hitbox swallows its
// neighbors and sliding between close buttons can never transfer).
static int find_button_base(float px, float py) {
    int best = -1;
    float best_d = 1e30f;
    for (int i = 0; i < n_ctls; i++) {
        ctl_t* c = &ctls[i];
        if (c->kind != K_BUTTON) continue;
        float dx = (px - c->cx) / (c->hx * REACH);
        float dy = (py - c->cy) / (c->hy * REACH);
        if (dx * dx + dy * dy > 1.0f) continue;
        float d = dx * dx + dy * dy;
        if (d < best_d) { best_d = d; best = i; }
    }
    return best;
}

static touch_t* touch_find(SDL_FingerID id) {
    for (int i = 0; i < 10; i++)
        if (touches[i].used && touches[i].id == id) return &touches[i];
    return NULL;
}

static touch_t* touch_alloc(SDL_FingerID id) {
    touch_t* t = touch_find(id);
    if (t) return t;
    for (int i = 0; i < 10; i++) {
        if (!touches[i].used) {
            touches[i].used = true;
            touches[i].id = id;
            touches[i].ctl = -1;
            return &touches[i];
        }
    }
    return NULL;
}

// Eightway: direction from angle, diagonals get DIAG_WINDOW_DEG-wide sectors.
// (RetroArch dpad_area semantics; glide between directions without lifting.)
static uint32_t eightway_bits(float dx, float dy, float radius) {
    float len = sqrtf(dx * dx + dy * dy);
    if (len < radius * DPAD_DEADZONE) return 0;
    float ang = atan2f(-dy, dx) * (180.0f / (float)M_PI); // y-down -> y-up
    if (ang < 0) ang += 360.0f;

    const float dw = DIAG_WINDOW_DEG * 0.5f;
    // diagonal centers 45/135/225/315
    if (fabsf(ang -  45.0f) <= dw) return WC_BUTTON_UP | WC_BUTTON_RIGHT;
    if (fabsf(ang - 135.0f) <= dw) return WC_BUTTON_UP | WC_BUTTON_LEFT;
    if (fabsf(ang - 225.0f) <= dw) return WC_BUTTON_DOWN | WC_BUTTON_LEFT;
    if (fabsf(ang - 315.0f) <= dw) return WC_BUTTON_DOWN | WC_BUTTON_RIGHT;
    if (ang >= 315.0f + dw || ang < 45.0f - dw)   return WC_BUTTON_RIGHT;
    if (ang < 135.0f - dw)                        return WC_BUTTON_UP;
    if (ang < 225.0f - dw)                        return WC_BUTTON_LEFT;
    return WC_BUTTON_DOWN;
}

static void press_ctl(int idx) {
    ctl_t* c = &ctls[idx];
    if (!c->pressed) {
        c->pressed = true;
        haptic_pulse(0.55f, 14);
    }
}

static void release_ctl(int idx) {
    if (idx < 0 || idx >= n_ctls) return;
    ctl_t* c = &ctls[idx];
    c->pressed = false;
    if (c->kind == K_DPAD) dpad_bits = 0;
    if (c->kind == K_STICK) {
        stick_x[c->stick] = stick_y[c->stick] = 0.0f;
        stick_held[c->stick] = false;
        if (c->drives_dpad) dpad_bits = 0;
    }
}

static void dial_update(touch_t* t, float px, float py) {
    ctl_t* c = &ctls[t->ctl];
    if (c->kind == K_DPAD) {
        uint32_t nb = eightway_bits(px - c->cx, py - c->cy, c->hx);
        if (nb != dpad_bits && nb != 0) haptic_pulse(0.35f, 9);
        dpad_bits = nb;
        return;
    }
    // K_STICK: radial deadzone, saturating throw, measured from the origin
    float throw_px = mm(STICK_THROW_MM) * STICK_SAT;
    float ax = (px - t->ox) / throw_px;
    float ay = (py - t->oy) / throw_px;
    float len = sqrtf(ax * ax + ay * ay);
    if (len < STICK_DEADZONE) {
        ax = ay = 0.0f;
    } else {
        float out = fminf(1.0f, (len - STICK_DEADZONE) / (1.0f - STICK_DEADZONE));
        ax = ax / len * out;
        ay = ay / len * out;
    }
    stick_x[c->stick] = ax;
    stick_y[c->stick] = ay;
    if (c->drives_dpad) {
        uint32_t nb = 0;
        if (ay < -STICK_DPAD_AT) nb |= WC_BUTTON_UP;
        if (ay >  STICK_DPAD_AT) nb |= WC_BUTTON_DOWN;
        if (ax < -STICK_DPAD_AT) nb |= WC_BUTTON_LEFT;
        if (ax >  STICK_DPAD_AT) nb |= WC_BUTTON_RIGHT;
        if (nb != dpad_bits && nb != 0) haptic_pulse(0.35f, 9);
        dpad_bits = nb;
    }
}

// The editor: every touch picks up a control, and drags it.
static bool edit_event(const SDL_Event* ev, float px, float py) {
    if (ev->type == SDL_FINGERDOWN) {
        int idx = find_ctl(px, py);
        if (idx < 0) return true;
        touch_t* t = touch_alloc(ev->tfinger.fingerId);
        if (!t) return true;
        t->ctl = idx;
        t->ox = ctls[idx].cx - px;
        t->oy = ctls[idx].cy - py;
        g_selected = ctls[idx].id;
        return true;
    }
    touch_t* t = touch_find(ev->tfinger.fingerId);
    if (!t || t->ctl < 0) return true;
    if (ev->type == SDL_FINGERMOTION) {
        ctl_t* c = &ctls[t->ctl];
        c->cx = fmaxf(c->hx * 0.5f, fminf((float)g_win_w - c->hx * 0.5f, px + t->ox));
        c->cy = fmaxf(c->hy * 0.5f, fminf((float)g_win_h - c->hy * 0.5f, py + t->oy));
        c->corner = 0;
        ctl_cfg_t* cc = &CTL_CFG(c->id);
        cc->moved = true;
        cc->fx = c->cx / (float)g_win_w;
        cc->fy = c->cy / (float)g_win_h;
    } else {
        t->used = false;
    }
    return true;
}

bool overlay_event(const SDL_Event* ev, int win_w, int win_h, double now_ms) {
    if (ev->type != SDL_FINGERDOWN && ev->type != SDL_FINGERMOTION &&
        ev->type != SDL_FINGERUP)
        return false;

    float px = ev->tfinger.x * (float)win_w;
    float py = ev->tfinger.y * (float)win_h;
    if (g_editing) return edit_event(ev, px, py);
    if (!g_visible || !cfg.show) return false;

    // Any touch wakes the dimmed controls, a control or not
    g_last_touch_ms = now_ms;

    if (ev->type == SDL_FINGERDOWN) {
        int idx = find_ctl(px, py);
        if (idx < 0) return false; // touched no control: the cart's touch
        touch_t* t = touch_alloc(ev->tfinger.fingerId);
        if (!t) return true;
        t->ctl = idx;
        ctl_t* c = &ctls[idx];
        if (c->kind == K_STICK) {
            t->ox = cfg.floating ? px : c->cx;
            t->oy = cfg.floating ? py : c->cy;
            stick_ox[c->stick] = t->ox;
            stick_oy[c->stick] = t->oy;
            stick_held[c->stick] = true;
            c->pressed = true;
            haptic_pulse(0.35f, 9);
            dial_update(t, px, py);
        } else if (c->kind == K_DPAD) {
            c->pressed = true;
            haptic_pulse(0.35f, 9);
            dial_update(t, px, py);
        } else {
            press_ctl(idx);
        }
        return true;
    }

    touch_t* t = touch_find(ev->tfinger.fingerId);
    if (!t || t->ctl < 0) return false;

    if (ev->type == SDL_FINGERMOTION) {
        ctl_t* c = &ctls[t->ctl];
        if (c->kind == K_DPAD || c->kind == K_STICK) {
            dial_update(t, px, py);
        } else if (c->kind == K_BUTTON) {
            // roll-off: sliding from one face button onto a neighbor presses
            // the neighbor and releases this one (PPSSPP touch gliding)
            int idx = find_button_base(px, py);
            if (idx >= 0 && idx != t->ctl) {
                release_ctl(t->ctl);
                t->ctl = idx;
                press_ctl(idx);
            }
            // off everything: keep held (range_mod semantics; dropping input
            // on a drifting thumb is the worse failure)
        }
        return true;
    }

    // SDL_FINGERUP
    release_ctl(t->ctl);
    t->used = false;
    return true;
}

// ─── Pad merge ──────────────────────────────────────────────────────────────

void overlay_apply(wc_pad_t* pad) {
    if (!g_visible || !cfg.show || g_editing) return;
    pad->connected = 1;
    pad->buttons |= dpad_bits;
    for (int i = 0; i < n_ctls; i++) {
        if (!ctls[i].pressed) continue;
        pad->buttons |= ctls[i].bits;
        if (ctls[i].trigger == 1) pad->left_trigger = WC_TRIGGER_MAX;
        if (ctls[i].trigger == 2) pad->right_trigger = WC_TRIGGER_MAX;
    }
    if (stick_x[0] != 0.0f || stick_y[0] != 0.0f) {
        pad->left_x = (int16_t)(stick_x[0] * 32767.0f);
        pad->left_y = (int16_t)(stick_y[0] * 32767.0f);
    }
    if (stick_x[1] != 0.0f || stick_y[1] != 0.0f) {
        pad->right_x = (int16_t)(stick_x[1] * 32767.0f);
        pad->right_y = (int16_t)(stick_y[1] * 32767.0f);
    }
}

// ─── Settings from the UI thread ────────────────────────────────────────────

void overlay_command(int key, int value) {
    if (!cmd_lock) return;
    SDL_LockMutex(cmd_lock);
    if (cmd_count < (int)(sizeof(cmd_queue) / sizeof(cmd_queue[0]))) {
        cmd_queue[cmd_count].key = key;
        cmd_queue[cmd_count].value = value;
        cmd_count++;
    }
    SDL_UnlockMutex(cmd_lock);
}

int overlay_get(int key) {
    switch (key) {
    case OV_SIZE: return cfg.size;
    case OV_OPACITY: return cfg.opacity;
    case OV_GROUPS: return cfg.groups < 0 ? cart_groups() : cfg.groups;
    case OV_FLOATING: return cfg.floating;
    case OV_HAPTICS: return cfg.haptics;
    case OV_SHOW: return cfg.show;
    default: return 0;
    }
}

static void apply_command(int key, int value) {
    switch (key) {
    case OV_EDIT:
        if (value && !g_editing) {
            for (int i = 0; i < n_ctls; i++) release_ctl(i);
            memset(touches, 0, sizeof(touches));
            g_selected = -1;
        } else if (!value && g_editing) {
            memset(touches, 0, sizeof(touches));
            g_selected = -1;
            cfg_save();
        }
        g_editing = value != 0;
        break;
    case OV_SIZE: cfg.size = clampi(value, 50, 200); relayout(); break;
    case OV_OPACITY: cfg.opacity = clampi(value, 5, 100); break;
    case OV_GROUPS: cfg.groups = clampi(value, 0, 15); relayout(); break;
    case OV_FLOATING: cfg.floating = value != 0; break;
    case OV_HAPTICS: cfg.haptics = value != 0; break;
    case OV_SHOW: cfg.show = value != 0; break;
    case OV_RESET: cfg_defaults(); g_selected = -1; relayout(); break;
    }
}

void overlay_pump(void) {
    if (!cmd_lock) return;
    SDL_LockMutex(cmd_lock);
    for (int i = 0; i < cmd_count; i++) apply_command(cmd_queue[i].key, cmd_queue[i].value);
    cmd_count = 0;
    SDL_UnlockMutex(cmd_lock);
}

bool overlay_editing(void) { return g_editing; }

// ─── Rendering ──────────────────────────────────────────────────────────────

static const char* VS =
    "#version 300 es\n"
    "uniform vec2 u_screen, u_center, u_half;\n"
    "out vec2 v_local;\n"
    "void main() {\n"
    "  vec2 c = vec2(float(gl_VertexID & 1) * 2.0 - 1.0,\n"
    "                float((gl_VertexID >> 1) & 1) * 2.0 - 1.0);\n"
    "  v_local = c;\n"
    "  vec2 px = u_center + c * u_half;\n"
    "  vec2 clip = px / u_screen * 2.0 - 1.0;\n"
    "  gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);\n"
    "}\n";

static const char* FS =
    "#version 300 es\n"
    "precision mediump float;\n"
    // u_half is also declared in the vertex shader, where floats default to
    // highp — GLSL ES requires a shared uniform's precision to match in both
    // stages, so pin it rather than inheriting mediump from the line above.
    "uniform highp vec2 u_half;\n"
    "uniform int u_shape, u_dirbits;\n"
    "uniform vec4 u_color, u_uv;\n"
    "uniform float u_param;\n"
    "uniform sampler2D u_font;\n"
    "in vec2 v_local;\n"
    "out vec4 frag;\n"
    "float sdBox(vec2 p, vec2 b) {\n"
    "  vec2 d = abs(p) - b;\n"
    "  return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);\n"
    "}\n"
    "void main() {\n"
    "  if (u_shape == 4) {            // label glyph\n"
    "    vec2 t = u_uv.xy + (v_local * 0.5 + 0.5) * u_uv.zw;\n"
    "    frag = vec4(u_color.rgb, u_color.a * texture(u_font, t).r);\n"
    "    return;\n"
    "  }\n"
    "  vec2 p = v_local * u_half;\n"
    "  float r = min(u_half.x, u_half.y) - 2.0;\n"
    "  float sd;\n"
    "  float boost = 0.0;\n"
    "  if (u_shape == 0) {            // circle\n"
    "    sd = length(p) - r;\n"
    "  } else if (u_shape == 1) {     // dpad: base disc + cross glyph\n"
    "    float base = length(p) - r;\n"
    "    float w = r * 0.30, al = r * 0.92;\n"
    "    float cross = min(sdBox(p, vec2(al, w)), sdBox(p, vec2(w, al)));\n"
    "    // brighten the held arm(s): bits UP=256 DOWN=512 LEFT=1024 RIGHT=2048\n"
    "    if ((u_dirbits & 256)  != 0 && p.y < -w) boost = 0.5;\n"
    "    if ((u_dirbits & 512)  != 0 && p.y >  w) boost = 0.5;\n"
    "    if ((u_dirbits & 1024) != 0 && p.x < -w) boost = 0.5;\n"
    "    if ((u_dirbits & 2048) != 0 && p.x >  w) boost = 0.5;\n"
    "    float ba = 1.0 - smoothstep(-1.5, 1.5, base);\n"
    "    float ca = 1.0 - smoothstep(-1.5, 1.5, cross);\n"
    "    frag = vec4(u_color.rgb * (1.0 + boost),\n"
    "                max(ba * u_color.a * 0.35, ca * u_color.a));\n"
    "    return;\n"
    "  } else if (u_shape == 2) {     // rounded rect\n"
    "    sd = sdBox(p, u_half - vec2(2.0 + u_param)) - u_param;\n"
    "  } else {                       // ring (stick base)\n"
    "    sd = abs(length(p) - r * 0.90) - r * 0.06;\n"
    "  }\n"
    "  float fill = 1.0 - smoothstep(-1.5, 1.5, sd);\n"
    "  float rim = 1.0 - smoothstep(0.0, 2.5, abs(sd));\n"
    "  float a = max(fill * u_color.a, rim * min(1.0, u_color.a * 1.9));\n"
    "  frag = vec4(u_color.rgb, a);\n"
    "}\n";

static GLuint compile(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(s, sizeof(log), NULL, log);
        wc_log_err("overlay %s shader: %s\n",
                   type == GL_VERTEX_SHADER ? "vertex" : "fragment", log);
    }
    return s;
}

static void font_init(void) {
    uint8_t pixels[FONT_N * FONT_CELL * 7];
    memset(pixels, 0, sizeof(pixels));
    for (size_t g = 0; g < FONT_N; g++)
        for (int y = 0; y < 7; y++)
            for (int x = 0; x < 5; x++)
                if (FONT[g][y] & (0x10 >> x))
                    pixels[y * FONT_N * FONT_CELL + g * FONT_CELL + x] = 255;

    // The cart is already loaded: leave its texture binding and unpack state as found
    GLint prev_tex = 0, prev_unit = 0, prev_align = 4;
    glGetIntegerv(GL_ACTIVE_TEXTURE, &prev_unit);
    glActiveTexture(GL_TEXTURE0);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &prev_tex);
    glGetIntegerv(GL_UNPACK_ALIGNMENT, &prev_align);
    glGenTextures(1, &font_tex);
    glBindTexture(GL_TEXTURE_2D, font_tex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, (GLsizei)(FONT_N * FONT_CELL), 7, 0,
                 GL_RED, GL_UNSIGNED_BYTE, pixels);
    // Nearest: smoothing a 5x7 font this far up closes its gaps, and 3 reads as B
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glPixelStorei(GL_UNPACK_ALIGNMENT, prev_align);
    glBindTexture(GL_TEXTURE_2D, (GLuint)prev_tex);
    glActiveTexture((GLenum)prev_unit);
}

void overlay_init(uint32_t cart_mask, float px_per_mm, const char* config_path) {
    g_cart_mask = cart_mask;
    if (px_per_mm > 1.0f) g_ppmm = px_per_mm;
    snprintf(g_config_path, sizeof(g_config_path), "%s", config_path ? config_path : "");
    cfg_load();
    cmd_lock = SDL_CreateMutex();

    GLuint vs = compile(GL_VERTEX_SHADER, VS);
    GLuint fs = compile(GL_FRAGMENT_SHADER, FS);
    prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glLinkProgram(prog);
    GLint linked = 0;
    glGetProgramiv(prog, GL_LINK_STATUS, &linked);
    if (!linked) {
        char log[512];
        glGetProgramInfoLog(prog, sizeof(log), NULL, log);
        wc_log_err("overlay program link: %s\n", log);
    }
    glDeleteShader(vs);
    glDeleteShader(fs);
    u_screen  = glGetUniformLocation(prog, "u_screen");
    u_center  = glGetUniformLocation(prog, "u_center");
    u_half    = glGetUniformLocation(prog, "u_half");
    u_shape   = glGetUniformLocation(prog, "u_shape");
    u_color   = glGetUniformLocation(prog, "u_color");
    u_dirbits = glGetUniformLocation(prog, "u_dirbits");
    u_param   = glGetUniformLocation(prog, "u_param");
    u_uv      = glGetUniformLocation(prog, "u_uv");
    u_font    = glGetUniformLocation(prog, "u_font");
    glGenVertexArrays(1, &vao);
    font_init();

    haptic_init();
}

void overlay_set_visible(bool visible) {
    if (!visible) {
        for (int i = 0; i < n_ctls; i++) release_ctl(i);
        memset(touches, 0, sizeof(touches));
    }
    g_visible = visible;
}

static void draw_ctl(float cx, float cy, float hx, float hy, int shape,
                     float r, float g, float b, float a, int dirbits, float param) {
    glUniform2f(u_center, cx, cy);
    glUniform2f(u_half, hx, hy);
    glUniform1i(u_shape, shape);
    glUniform4f(u_color, r, g, b, a);
    glUniform1i(u_dirbits, dirbits);
    glUniform1f(u_param, param);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

// Centered text in FONT_CHARS. height = glyph height in px.
static void draw_label(const char* s, float cx, float cy, float height, float a) {
    size_t n = strlen(s);
    float w = height * 5.0f / 7.0f, adv = w * 1.25f;
    float x = cx - adv * (float)(n - 1) * 0.5f;
    const float tw = (float)(FONT_N * FONT_CELL);
    for (size_t i = 0; i < n; i++, x += adv) {
        const char* at = strchr(FONT_CHARS, s[i]);
        if (!at) continue;
        float u = (float)((at - FONT_CHARS) * FONT_CELL) / tw;
        glUniform4f(u_uv, u, 0.0f, 5.0f / tw, 1.0f);
        draw_ctl(x, cy, w * 0.5f, height * 0.5f, SH_GLYPH, 1.0f, 1.0f, 1.0f, a, 0, 0.0f);
    }
}

void overlay_render(bool save_gl_state, double now_ms) {
    // advance fades even while invisible so re-show starts from reality
    float dt = (g_last_render_ms > 0.0) ? (float)(now_ms - g_last_render_ms) : 16.7f;
    g_last_render_ms = now_ms;
    if (dt > 100.0f) dt = 100.0f;
    if (g_last_touch_ms < 0.0) g_last_touch_ms = now_ms; // fresh start: not idle yet

    bool shown = g_editing || (g_visible && cfg.show);
    float fade_target = shown ? 1.0f : 0.0f;
    g_fade += (fade_target - g_fade) * fminf(1.0f, dt / (float)FADE_MS);
    float dim_target = (!g_editing && now_ms - g_last_touch_ms > IDLE_DIM_MS) ? DIM_FACTOR : 1.0f;
    g_dim += (dim_target - g_dim) * fminf(1.0f, dt / (float)DIM_FADE_MS);

    float base = fminf(1.0f, (float)cfg.opacity / 100.0f);
    if (g_editing) base = fmaxf(base, 0.6f); // what is being arranged must be visible
    float alpha = base * g_fade * g_dim;
    if (alpha < 0.01f) return;

    // GL carts keep internal state caches (Ganesh, gl4es); save what we touch.
    // The framebuffer above all: the host keeps a GL cart bound to its redirect
    // between frames, and a cart never rebinds what it thinks is the default
    // framebuffer. Left on the real one, its next frame misses the redirect and
    // the host's blit shows black.
    GLint prev_prog = 0, prev_vao = 0, prev_fbo = 0, prev_tex = 0, prev_unit = 0, prev_sampler = 0;
    GLint prev_src_rgb = 0, prev_dst_rgb = 0, prev_src_a = 0, prev_dst_a = 0;
    GLint prev_vp[4] = {0};
    GLboolean prev_blend_on = GL_FALSE, prev_depth_on = GL_FALSE,
              prev_scissor_on = GL_FALSE, prev_stencil_on = GL_FALSE, prev_cull_on = GL_FALSE;
    if (save_gl_state) {
        glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prev_fbo);
        glGetIntegerv(GL_CURRENT_PROGRAM, &prev_prog);
        glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &prev_vao);
        glGetIntegerv(GL_ACTIVE_TEXTURE, &prev_unit);
        glActiveTexture(GL_TEXTURE0);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &prev_tex);
        glGetIntegerv(GL_SAMPLER_BINDING, &prev_sampler);
        glGetIntegerv(GL_BLEND_SRC_RGB, &prev_src_rgb);
        glGetIntegerv(GL_BLEND_DST_RGB, &prev_dst_rgb);
        glGetIntegerv(GL_BLEND_SRC_ALPHA, &prev_src_a);
        glGetIntegerv(GL_BLEND_DST_ALPHA, &prev_dst_a);
        glGetIntegerv(GL_VIEWPORT, prev_vp);
        prev_blend_on = glIsEnabled(GL_BLEND);
        prev_depth_on = glIsEnabled(GL_DEPTH_TEST);
        prev_scissor_on = glIsEnabled(GL_SCISSOR_TEST);
        prev_stencil_on = glIsEnabled(GL_STENCIL_TEST);
        prev_cull_on = glIsEnabled(GL_CULL_FACE);
    }

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, g_win_w, g_win_h);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_CULL_FACE);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glUseProgram(prog);
    glBindVertexArray(vao);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, font_tex);
    glBindSampler(0, 0);
    glUniform1i(u_font, 0);
    glUniform2f(u_screen, (float)g_win_w, (float)g_win_h);

    for (int i = 0; i < n_ctls; i++) {
        ctl_t* c = &ctls[i];
        float target = c->pressed ? 1.0f : 0.0f;
        c->press_anim += (target - c->press_anim) * fminf(1.0f, dt / 60.0f);
        float a = alpha * (1.0f + 1.6f * c->press_anim);
        if (a > 0.95f) a = 0.95f;
        float grow = 1.0f + 0.06f * c->press_anim;

        if (g_editing && c->id == g_selected)
            draw_ctl(c->cx, c->cy, c->hx + 6.0f, c->hy + 6.0f,
                     c->kind == K_RECT ? SH_RRECT : SH_RING, 1.0f, 1.0f, 1.0f, 0.5f, 0, c->hy * 0.6f);

        if (c->kind == K_DPAD) {
            draw_ctl(c->cx, c->cy, c->hx * grow, c->hy * grow, SH_DPAD,
                     c->r, c->g, c->b, a, dpad_bits, 0.0f);
        } else if (c->kind == K_STICK) {
            bool held = stick_held[c->stick];
            float bx = held ? stick_ox[c->stick] : c->cx;
            float by = held ? stick_oy[c->stick] : c->cy;
            draw_ctl(bx, by, c->hx, c->hy, SH_RING, c->r, c->g, c->b, a, 0, 0.0f);
            float knob_r = c->hx * 0.46f;
            float throw_px = mm(STICK_THROW_MM);
            draw_ctl(bx + stick_x[c->stick] * throw_px, by + stick_y[c->stick] * throw_px,
                     knob_r, knob_r, SH_CIRCLE, c->r, c->g, c->b,
                     fminf(0.95f, a * 1.5f), 0, 0.0f);
        } else if (c->kind == K_BUTTON) {
            draw_ctl(c->cx, c->cy, c->hx * grow, c->hy * grow, SH_CIRCLE,
                     c->r, c->g, c->b, a, 0, 0.0f);
        } else {
            draw_ctl(c->cx, c->cy, c->hx * grow, c->hy * grow, SH_RRECT,
                     c->r, c->g, c->b, a, 0, c->hy * 0.6f);
        }
        if (c->label) {
            // Readable before the shape is: labels sit above the control's opacity
            float h = fminf(c->hy, c->hx) * (strlen(c->label) > 1 ? 0.65f : 0.8f);
            draw_label(c->label, c->cx, c->cy, h, fminf(0.95f, a * 2.2f));
        }
    }

    if (save_gl_state) {
        glBindSampler(0, (GLuint)prev_sampler);
        glBindTexture(GL_TEXTURE_2D, (GLuint)prev_tex);
        glActiveTexture((GLenum)prev_unit);
        glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)prev_fbo);
        glUseProgram((GLuint)prev_prog);
        glBindVertexArray((GLuint)prev_vao);
        if (!prev_blend_on) glDisable(GL_BLEND);
        glBlendFuncSeparate((GLenum)prev_src_rgb, (GLenum)prev_dst_rgb,
                            (GLenum)prev_src_a, (GLenum)prev_dst_a);
        if (prev_depth_on) glEnable(GL_DEPTH_TEST);
        if (prev_scissor_on) glEnable(GL_SCISSOR_TEST);
        if (prev_stencil_on) glEnable(GL_STENCIL_TEST);
        if (prev_cull_on) glEnable(GL_CULL_FACE);
        glViewport(prev_vp[0], prev_vp[1], prev_vp[2], prev_vp[3]);
    }
}

void overlay_shutdown(void) {
    cfg_save();
    if (haptic) { SDL_HapticClose(haptic); haptic = NULL; }
    if (prog) { glDeleteProgram(prog); prog = 0; }
    if (vao) { glDeleteVertexArrays(1, &vao); vao = 0; }
    if (font_tex) { glDeleteTextures(1, &font_tex); font_tex = 0; }
}
