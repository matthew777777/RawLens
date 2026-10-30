// SPDX-License-Identifier: GPL-3.0-or-later
#include <craw/Writer.h>
#include <cassert>
#include <cerrno>
#include <csignal>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>
#include <iostream>
#include <vector>

int main() {
    std::FILE* owner = std::tmpfile();
    assert(owner);
    const int fd = fileno(owner);
    const int duplicate = dup(fd);
    assert(duplicate >= 0);
    {
        craw::Writer writer(fdopen(duplicate, "wb"), "{}");
        std::vector<uint16_t> raw(32 * 32, 1024);
        craw::FrameDesc desc;
        desc.width = 32;
        desc.height = 32;
        desc.bitDepth = 12;
        desc.cfa = craw::Cfa::RGGB;
        writer.addFrame(raw.data(), 32, desc, 0, "{}");
        assert(writer.frameCount() == 1);
        writer.close();
    }
    assert(fcntl(fd, F_GETFD) >= 0); // Writer must not close the caller's descriptor.
    assert(fcntl(duplicate, F_GETFD) < 0 && errno == EBADF);
    assert(lseek(fd, -8, SEEK_END) >= 0);
    char footer[8];
    assert(read(fd, footer, sizeof footer) == sizeof footer);
    assert(std::memcmp(footer, "CRAWEND\0", 8) == 0);
    assert(lseek(fd, 0, SEEK_SET) == 0);
    char head[4];
    assert(read(fd, head, sizeof head) == sizeof head);
    assert(std::memcmp(head, "CRAW", 4) == 0);
    fclose(owner);

    // Unplug/write error analogue: closing a broken stream must report failure
    // and release its own descriptor without closing the caller's descriptor.
    std::signal(SIGPIPE, SIG_IGN);
    int ends[2];
    assert(pipe(ends) == 0);
    close(ends[0]);
    const int broken = dup(ends[1]);
    bool failed = false;
    try {
        craw::Writer writer(fdopen(broken, "wb"), "{}");
        writer.close();
    } catch (...) { failed = true; }
    assert(failed);
    assert(fcntl(broken, F_GETFD) < 0 && errno == EBADF);
    assert(fcntl(ends[1], F_GETFD) >= 0);
    close(ends[1]);
    std::cout << "PASS: FD CRAW frame/footer, caller ownership, and write-failure cleanup\n";
}
