/* Read-only media graph survey. No links, formats, controls or streams are changed. */
#include <errno.h>
#include <fcntl.h>
#include <linux/media.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

int main(void) {
    for (int n = 0; n < 8; ++n) {
        char path[32];
        snprintf(path, sizeof(path), "/dev/media%d", n);
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        struct media_device_info info = {0};
        if (ioctl(fd, MEDIA_IOC_DEVICE_INFO, &info) < 0) {
            fprintf(stderr, "%s: %s\n", path, strerror(errno));
            close(fd);
            continue;
        }
        printf("MEDIA %s driver=%s model=%s\n", path, info.driver, info.model);
        struct media_entity_desc e = {0};
        while (1) {
            e.id |= MEDIA_ENT_ID_FLAG_NEXT;
            if (ioctl(fd, MEDIA_IOC_ENUM_ENTITIES, &e) < 0) break;
            printf("ENTITY id=%u name=%s type=%#x dev=%u:%u pads=%u links=%u\n",
                   e.id, e.name, e.type, e.dev.major, e.dev.minor, e.pads, e.links);
            struct media_links_enum links = {.entity = e.id};
            links.pads = calloc(e.pads ? e.pads : 1, sizeof(*links.pads));
            links.links = calloc(e.links ? e.links : 1, sizeof(*links.links));
            if (!links.pads || !links.links) return 1;
            if (ioctl(fd, MEDIA_IOC_ENUM_LINKS, &links) == 0) {
                for (unsigned int i = 0; i < e.links; ++i) {
                    struct media_link_desc *l = &links.links[i];
                    if (l->source.entity != e.id) continue;
                    printf("LINK %u:%u -> %u:%u flags=%#x\n", l->source.entity,
                           l->source.index, l->sink.entity, l->sink.index, l->flags);
                }
            }
            free(links.pads);
            free(links.links);
        }
        close(fd);
    }
    return 0;
}
