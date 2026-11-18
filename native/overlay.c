/* Camera-layer alpha control for units whose device tree shows the tcc803x display layout.
 * Boot-started by NEMESIS for CAP_SYS_RAWIO. Only uid 1000 can request a camera
 * lease. During a lease it checks the UI layer every millisecond (guard_ui) and blacks the camera
 * out in the mixer (black_out); no framebuffer copies, overlay routing or mixer priority changes.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/system_properties.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define ENDPOINT "dev.skramer.dashcam.overlay"
#define AEN (1u << 11)
#define ASEL (1u << 24)
#define UPDATE (1u << 16)
#define ENABLE (1u << 28)
#define ALPHA_MASK 0x00ff00ffu
static volatile sig_atomic_t stopping;
static void stop(int sig) { (void)sig; stopping = 1; }
static volatile uint32_t *regs;
/* What a lease changed. Shared with the supervising parent (see supervise), so that a helper
 * that dies without cleanup still gets its changes undone. */
struct lease { uint32_t saved_bits, saved_alpha, saved_size; int hidden, blacked; };
static struct lease own_lease, *st = &own_lease;
static uint32_t camera_base, camera_end;
#define REG(o) regs[(o) / 4]

/* Switching screens, the hardware composer hides the UI layer (RDMA0) for a few frames
 * (tc_hide_fbTarget: framebuffer ioctl 0x91 sets its constant alpha to 0) so video layers under
 * it show. Where nothing is under the UI, the mixer shows UI pixels unblended (WMIX color control,
 * Telechips' default 0x20c44cc4: alpha only counts where both layers have pixels), so on a stock
 * unit the last frame simply stays up. The camera layer is under the UI, so there the camera
 * showed instead; the camera's own alpha doesn't count either, with nothing under it. While the
 * camera is hidden the helper checks every GUARD_MS and, finding the UI hidden that way with no
 * video layer on (RDMA2 and RDMA3 off), makes the UI's constant alpha opaque so its last frame
 * shows, as on a stock unit. A screen refresh can still come between the composer's hide and the
 * check, so now and then one frame of camera shows. With a video layer on, the UI is left
 * see-through for it, and the camera's alpha works there. */
#define UI_OPAQUE 0x00ff00ffu
#define GUARD_MS 1

/* That leaves the refresh the guard can miss, and UI pixels that aren't fully opaque (the
 * composer's framebuffer during some app transitions): the camera showed through both. So during
 * a lease the camera is also blacked out in the mixer. WMIX0 (tcc802x/803x layout: control at
 * 0x00, size at 0x08, then an alpha block every 0x10 from 0x40) stacks the layers by overlay
 * priority; at OVP 24 the UI (RDMA0) is layer 3, the camera (RDMA1) layer 2 and the video layers
 * below it, and alpha block 1 lays layer 2 over what's under it. Its color control (Telechips'
 * default 0x20c44cc4) takes the camera's pixels as they are where nothing is under them (regions
 * 00 and 10: pixel x 256); with pixel x the camera's alpha there instead, and that alpha 0, the
 * camera is black where it's alone and still blends away over video (region 11 is untouched). The
 * stock view never sees it: restore puts the default back before the camera is handed over. */
#define WMIX_CTRL 0x1800
#define WMIX_CAMERA_ACON 0x1850
#define WMIX_CAMERA_CCON 0x1854
#define WMIX_CAMERA_ROPC 0x1858
#define ROPC_DEFAULT 0x0000c018u
#define WMIX_UPDATE (1u << 16)
#define OVP_UI_CAMERA_VIDEO 24
#define ACON_DEFAULT 0x10622662u
#define CCON_DEFAULT 0x20c44cc4u
#define CCON_BLACK (CCON_DEFAULT & ~0x000f000fu)

static void wmix_ccon(uint32_t value) {
    REG(WMIX_CAMERA_CCON) = value;
    __sync_synchronize();
    REG(WMIX_CTRL) |= WMIX_UPDATE;
    __sync_synchronize();
}

/* Blacks out the camera where nothing is under it, if the mixer is laid out as expected and
 * nobody else has changed that color control. Without it, the UI guard alone still works. */
static void black_out(void) {
    uint32_t ctrl = REG(WMIX_CTRL), acon = REG(WMIX_CAMERA_ACON), ccon = REG(WMIX_CAMERA_CCON),
        ropc = REG(WMIX_CAMERA_ROPC);
    if (st->blacked) return;
    /* ROPC's alpha selection must be the default (the layers' own alpha), or the black value
     * would take a constant alpha that the camera driver's restart doesn't reset. A black value
     * already there can only be one of ours that wasn't undone: take it over, so it is. */
    if ((ctrl & 31) != OVP_UI_CAMERA_VIDEO || acon != ACON_DEFAULT || ropc != ROPC_DEFAULT ||
            (ccon != CCON_DEFAULT && ccon != CCON_BLACK)) {
        fprintf(stderr, "overlay: camera not blacked out: wmix0 ctrl %08x acon/ccon %08x %08x %08x %08x "
                "%08x %08x, ropc %08x\n", ctrl, REG(0x1840), REG(0x1844), acon, ccon, REG(0x1860), REG(0x1864),
                ropc);
        return;
    }
    if (ccon != CCON_BLACK) wmix_ccon(CCON_BLACK);
    st->blacked = 1;
    fprintf(stderr, "overlay: camera blacked out where nothing is under it%s\n",
            ccon == CCON_BLACK ? " (taken over from an earlier lease)" : "");
}

/* For a black value no live helper owns (one left from before a reboot can't be, but be sure). */
static void black_leftover(void) {
    if (REG(WMIX_CAMERA_CCON) != CCON_BLACK) return;
    wmix_ccon(CCON_DEFAULT);
    fprintf(stderr, "overlay: put back the mixer's default, left blacked out by an earlier helper\n");
}

/* Puts the mixer's default back, unless something else has changed it since. The release is
 * acknowledged after this, and the camera handed over only then, so it waits (up to 40 ms) for
 * the mixer to take the default at the next frame: its update bit clears once it has. */
static void black_back(void) {
    if (!st->blacked) return;
    st->blacked = 0;
    if (REG(WMIX_CAMERA_CCON) != CCON_BLACK) return;
    wmix_ccon(CCON_DEFAULT);
    struct timespec frame_part = {0, 2000000};
    int waits = 0;
    while ((REG(WMIX_CTRL) & WMIX_UPDATE) && waits++ < 20) nanosleep(&frame_part, NULL);
    fprintf(stderr, "overlay: camera no longer blacked out%s\n",
            REG(WMIX_CTRL) & WMIX_UPDATE ? " (mixer update still pending after 40 ms)" : "");
}

/* Puts back the UI's constant alpha of 0 if it's our opaque one, for the next frame. */
static void ui_alpha_back(void) {
    if ((REG(0x424) & ALPHA_MASK) != UI_OPAQUE) return;
    REG(0x424) &= ~ALPHA_MASK;
    __sync_synchronize();
    REG(0x400) |= UPDATE;
    __sync_synchronize();
}

/* One check of the UI layer; see above. */
static void guard_ui(void) {
    /* The camera driver restarting the camera under the lease resets the camera's alpha (and
     * with that whether black is right); let the mixer go back to showing it. */
    if (st->blacked && (REG(0x500) & (AEN | ASEL)) != AEN) black_back();
    uint32_t ui = REG(0x400), alpha = REG(0x424) & ALPHA_MASK;
    int hidden_ui = (ui & ENABLE) && (ui & AEN) && !(ui & ASEL);
    int video = (REG(0x600) & ENABLE) || (REG(0x700) & ENABLE);
    if (hidden_ui && !video && alpha == 0) {
        REG(0x424) |= UI_OPAQUE;
        __sync_synchronize();
        REG(0x400) |= UPDATE;
        __sync_synchronize();
        fprintf(stderr, "overlay: UI hidden with nothing under the camera; holding its last frame\n");
    } else if (alpha == UI_OPAQUE && hidden_ui && video) {
        ui_alpha_back();
        fprintf(stderr, "overlay: a video layer came on; UI see-through again\n");
    }
}

static void restore(void) {
    if (!st->hidden) return;
    ui_alpha_back();
    black_back();
    uint32_t ctrl = REG(0x500);
    /* A camera restart resets AEN. Do not restore saved values into a new
     * session after the driver has already restored its own settings. */
    if ((ctrl & (AEN | ASEL)) == AEN && REG(0x508) == st->saved_size) {
        REG(0x524) = (REG(0x524) & ~ALPHA_MASK) | st->saved_alpha;
        __sync_synchronize();
        REG(0x500) = (REG(0x500) & ~(AEN | ASEL)) | st->saved_bits | UPDATE;
        __sync_synchronize();
    }
    st->hidden = 0;
    fprintf(stderr, "overlay: restored camera alpha\n");
}

static int hide(unsigned w, unsigned h) {
    uint32_t ctrl = REG(0x500), base = REG(0x50c);
    uint64_t ring = (uint64_t)w * h * 16;
    if (st->hidden || w < 64 || h < 64 || w > 1920 || h > 1080 || (w | h) & 1 ||
        !(ctrl & ENABLE) || (ctrl & 31) != 12 ||
        REG(0x508) != ((h << 16) | w) || base < camera_base ||
        (uint64_t)base + (uint64_t)w * h * 4 > camera_end ||
        ring > camera_end - camera_base || REG(0x1808) != 0x02d00780) {
        fprintf(stderr, "overlay: refused %ux%u: rdma1 ctrl %08x size %08x base %08x, camera ram %08x-%08x, "
                "wmix0 size %08x%s\n", w, h, ctrl, REG(0x508), base, camera_base, camera_end, REG(0x1808),
                st->hidden ? ", already transparent" : "");
        return 0;
    }
    st->saved_bits = ctrl & (AEN | ASEL);
    st->saved_alpha = REG(0x524) & ALPHA_MASK;
    st->saved_size = REG(0x508);
    st->hidden = 1;
    REG(0x524) &= ~ALPHA_MASK;
    __sync_synchronize();
    REG(0x500) = (REG(0x500) & ~ASEL) | AEN | UPDATE;
    __sync_synchronize();
    fprintf(stderr, "overlay: camera transparent (%ux%u)\n", w, h);
    black_out();
    return 1;
}

static int read_request(int fd, unsigned char *buf, size_t size);

/* Waits for the client's release byte (or its end), guarding the UI layer meanwhile. */
static void await_release(int client) {
    unsigned char release;
    struct pollfd p = {client, POLLIN, 0};
    while (!stopping) {
        int r = poll(&p, 1, GUARD_MS);
        if (r > 0 || (r < 0 && errno != EINTR)) break;
        if (r == 0) guard_ui();
    }
    if (!stopping && (p.revents & POLLIN)) recv(client, &release, 1, MSG_DONTWAIT);
}

static int read_request(int fd, unsigned char *buf, size_t size) {
    while (size && !stopping) {
        ssize_t n = recv(fd, buf, size, 0);
        if (n <= 0) return 0;
        buf += n; size -= (size_t)n;
    }
    return !stopping;
}

/* Big-endian 32-bit cells of a device-tree property; how many were read. */
static int dt_cells(const char *path, uint32_t *cells, int max) {
    unsigned char raw[64];
    int fd = open(path, O_RDONLY | O_CLOEXEC), n = 0;
    if (fd < 0) return 0;
    ssize_t len = read(fd, raw, sizeof raw);
    close(fd);
    for (; len >= 4 * (n + 1) && n < max; n++)
        cells[n] = (uint32_t)raw[4 * n] << 24 | (uint32_t)raw[4 * n + 1] << 16 | (uint32_t)raw[4 * n + 2] << 8 | raw[4 * n + 3];
    return n;
}

/* The display block at 0x12000000, and the main display's layers RDMA 0-3 (the camera's RDMA1):
 * the layout every register access here assumes. */
static int vioc_layout(void) {
    uint32_t reg[2], rdma[5];
    if (dt_cells("/proc/device-tree/fbdisplay@12000000/reg", reg, 2) != 2 ||
        reg[0] != 0x12000000 || reg[1] < 0x2000) return 0;
    return dt_cells("/proc/device-tree/fbdisplay@12000000/fbdisplay0@12000000/telechips,rdma", rdma, 5) == 5 &&
        rdma[1] == 0 && rdma[2] == 1 && rdma[3] == 2 && rdma[4] == 3;
}

/* The helper proper runs in a child; this parent only waits. A child that dies without cleanup
 * (SIGKILL from the low-memory killer, a crash) would otherwise leave the camera transparent and
 * blacked out until a reboot: a helper NEMESIS restarts later can't open /dev/mem. The parent
 * keeps the mapping and the lease state, undoes what the child left, and starts another child,
 * which also keeps the overlay working without a reboot. If the parent dies, the child is sent
 * SIGTERM and cleans up itself. Returns in the child; the parent exits from here. */
static void supervise(void) {
    for (;;) {
        pid_t child = fork();
        if (child < 0) { perror("overlay: fork (running unsupervised)"); return; }
        if (child == 0) {
            prctl(PR_SET_PDEATHSIG, SIGTERM);
            if (getppid() == 1) _exit(0);  /* the parent already went */
            return;
        }
        int status = 0;
        while (waitpid(child, &status, 0) < 0) {
            if (errno != EINTR) { perror("overlay: waitpid"); break; }
            if (stopping) kill(child, SIGTERM);
        }
        if (st->hidden || st->blacked) {
            fprintf(stderr, "overlay: helper %d ended (status %#x) during a lease; undoing it\n", (int)child, status);
            restore();
        }
        if (stopping) exit(0);
        /* An ordinary exit is a failure that another child would only repeat. */
        if (WIFEXITED(status)) exit(WEXITSTATUS(status));
        fprintf(stderr, "overlay: helper %d ended (status %#x); starting another\n", (int)child, status);
        sleep(1);
        if (stopping) exit(0);
    }
}

/* Nothing to do on this unit: stay up so NEMESIS doesn't restart (and log) it every 10 s. */
static int idle(const char *why, const char *device) {
    fprintf(stderr, "overlay: %s on %s; no register access\n", why, device);
    for (;;) pause();
}

int main(void) {
    char device[PROP_VALUE_MAX] = "";
    __system_property_get("ro.product.device", device);
    if (!vioc_layout()) return idle("different display layout", device);
    FILE *pmap = fopen("/proc/pmap", "r");
    char line[256], name[128]; unsigned base, size;
    while (pmap && fgets(line, sizeof line, pmap)) {
        if (sscanf(line, "%x %x %127s", &base, &size, name) == 3 && !strcmp(name, "rearcamera")) {
            if (size <= UINT32_MAX - base) { camera_base = base; camera_end = base + size; }
            break;
        }
    }
    if (pmap) fclose(pmap);
    if (!camera_base || camera_end <= camera_base) return idle("no rearcamera region in /proc/pmap", device);
    fprintf(stderr, "overlay: %s, camera ram %08x-%08x\n", device, camera_base, camera_end);
    int mem = open("/dev/mem", O_RDWR | O_SYNC | O_CLOEXEC);
    if (mem < 0) { perror("overlay: /dev/mem (reboot after install/update)"); return 1; }
    regs = mmap(NULL, 0x2000, PROT_READ | PROT_WRITE, MAP_SHARED, mem, 0x12000000);
    close(mem);
    if (regs == MAP_FAILED) { perror("overlay: mmap"); return 1; }
    black_leftover();
    /* The UI guard has to see the composer's hide before the next screen refresh, with the
     * recorder keeping the CPUs busy: lowest real-time priority. It sleeps between checks. */
    struct sched_param rt = {1};
    if (sched_setscheduler(0, SCHED_FIFO, &rt)) perror("overlay: real-time priority");
    /* Keep the mapping; give up root and all capabilities before listening. */
    if (setgid(1000) || setuid(1000)) { perror("overlay: drop root"); return 1; }
    struct sigaction action; memset(&action, 0, sizeof action);
    action.sa_handler = stop;
    sigaction(SIGTERM, &action, NULL); sigaction(SIGINT, &action, NULL);
    sigaction(SIGHUP, &action, NULL); signal(SIGPIPE, SIG_IGN);
    struct lease *shared = mmap(NULL, sizeof *shared, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (shared != MAP_FAILED) { st = shared; supervise(); }
    else perror("overlay: shared lease state (running unsupervised)");
    int listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_un address; memset(&address, 0, sizeof address);
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path + 1, ENDPOINT, sizeof ENDPOINT - 1);
    if (listener < 0 || bind(listener, (struct sockaddr *)&address,
            offsetof(struct sockaddr_un, sun_path) + sizeof ENDPOINT) || listen(listener, 2)) {
        perror("overlay: socket"); return 1;
    }
    fprintf(stderr, "overlay: ready\n");
    while (!stopping) {
        int client = accept(listener, NULL, NULL);
        if (client < 0) { if (errno == EINTR) continue; break; }
        struct ucred peer; socklen_t length = sizeof peer;
        if (getsockopt(client, SOL_SOCKET, SO_PEERCRED, &peer, &length) || peer.uid != 1000) {
            close(client); continue;
        }
        /* Bound partial commands, then sleep indefinitely for release/death. */
        struct timeval timeout = {0, 500000};
        setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof timeout);
        unsigned char request[5];
        int ok = read_request(client, request, sizeof request) && request[0] == 'H' &&
            hide(((unsigned)request[1] << 8) | request[2], ((unsigned)request[3] << 8) | request[4]);
        if (ok) {
            timeout.tv_usec = 0;
            setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof timeout);
            if (send(client, "H", 1, MSG_NOSIGNAL) == 1) await_release(client);
            restore();
            send(client, "S", 1, MSG_NOSIGNAL);
        } else send(client, "N", 1, MSG_NOSIGNAL);
        close(client);
    }
    restore(); close(listener); munmap((void *)regs, 0x2000);
    return 0;
}
