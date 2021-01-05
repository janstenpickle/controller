FROM debian

COPY controller-plugin-kodi-server /bin/

RUN useradd --system --create-home --uid 9000 --gid 0 controller
USER 9000:0

ENTRYPOINT ["/bin/controller-plugin-kodi-server"]