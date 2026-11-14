// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

// Raw emulator input must traverse InputReader and the accessibility input filter.
// InputManager's ordinary injected gestures bypass that filter.
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/ioctl.h>

static void event(int fd, int type, int code, int value) {
    struct input_event input = {0};
    input.type = type;
    input.code = code;
    input.value = value;
    if (write(fd, &input, sizeof(input)) != sizeof(input)) { perror("write input"); exit(1); }
}

static void position(int fd, int x, int y, int rotation) {
    int natural_x = x, natural_y = y;
    if (rotation == 1) { natural_x = 32767 - y; natural_y = x; }
    else if (rotation == 2) { natural_x = 32767 - x; natural_y = 32767 - y; }
    else if (rotation == 3) { natural_x = y; natural_y = 32767 - x; }
    event(fd, EV_ABS, ABS_MT_POSITION_X, natural_x);
    event(fd, EV_ABS, ABS_MT_POSITION_Y, natural_y);
}

int main(int count, char **args) {
    if (count < 3) return 2;
    if (strcmp(args[2], "keyboard") == 0) {
        int keyboard = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
        if (keyboard < 0) { perror("uinput"); return 1; }
        ioctl(keyboard, UI_SET_EVBIT, EV_KEY);
        int codes[] = {KEY_A, KEY_B, KEY_C, KEY_D, KEY_ENTER, KEY_LEFTALT, KEY_RIGHT};
        for (unsigned int i = 0; i < sizeof(codes) / sizeof(codes[0]); i++)
            ioctl(keyboard, UI_SET_KEYBIT, codes[i]);
        struct uinput_user_dev device = {0};
        strcpy(device.name, "Pocket E2 QA switches");
        device.id.bustype = BUS_USB;
        device.id.vendor = 0x18d1;
        device.id.product = 0x4ee7;
        if (write(keyboard, &device, sizeof(device)) != sizeof(device)
                || ioctl(keyboard, UI_DEV_CREATE) < 0) { perror("create keyboard"); return 1; }
        puts("READY virtual QA switches; close stdin to remove");
        fflush(stdout);
        while (getchar() != EOF) {}
        ioctl(keyboard, UI_DEV_DESTROY);
        close(keyboard);
        return 0;
    }
    int fd = open(args[1], O_WRONLY);
    if (fd < 0) { perror("open input"); return 1; }
    if (strcmp(args[2], "right") == 0 || strcmp(args[2], "left") == 0) {
        int rotation = count == 4 ? atoi(args[3]) : 0;
        if (rotation < 0 || rotation > 3) return 2;
        event(fd, EV_ABS, ABS_MT_SLOT, 0);
        event(fd, EV_ABS, ABS_MT_TRACKING_ID, getpid() & 65535);
        event(fd, EV_ABS, ABS_MT_PRESSURE, 100);
        for (int i = 0; i <= 20; i++) {
            int x = strcmp(args[2], "left") == 0 ? 24571 - i * 819 : 8191 + i * 819;
            position(fd, x, 16383, rotation);
            event(fd, EV_SYN, SYN_REPORT, 0);
            usleep(5000);
        }
        event(fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
        event(fd, EV_SYN, SYN_REPORT, 0);
    } else if (strcmp(args[2], "activate") == 0 || strcmp(args[2], "longpress") == 0) {
        int x = count >= 5 ? atoi(args[3]) : 16383;
        int y = count >= 5 ? atoi(args[4]) : 16383;
        int rotation = count == 6 ? atoi(args[5]) : 0;
        if (x < 0 || x > 32767 || y < 0 || y > 32767 || rotation < 0 || rotation > 3) return 2;
        for (int tap = 0; tap < 2; tap++) {
            event(fd, EV_ABS, ABS_MT_SLOT, 0);
            event(fd, EV_ABS, ABS_MT_TRACKING_ID, (getpid() + tap) & 65535);
            position(fd, x, y, rotation);
            event(fd, EV_ABS, ABS_MT_PRESSURE, 100);
            event(fd, EV_SYN, SYN_REPORT, 0);
            usleep(tap == 1 && strcmp(args[2], "longpress") == 0 ? 1200000 : 50000);
            event(fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
            event(fd, EV_SYN, SYN_REPORT, 0);
            usleep(80000);
        }
    } else if (strcmp(args[2], "key") == 0 && count == 4) {
        event(fd, EV_KEY, atoi(args[3]), 1);
        event(fd, EV_SYN, SYN_REPORT, 0);
        usleep(50000);
        event(fd, EV_KEY, atoi(args[3]), 0);
        event(fd, EV_SYN, SYN_REPORT, 0);
    } else return 2;
    close(fd);
    return 0;
}
