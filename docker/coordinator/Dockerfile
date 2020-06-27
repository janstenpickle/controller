FROM debian

COPY controller-coordinator /bin/

RUN useradd --system --create-home --uid 9000 --gid 0 controller
USER 9000:0

ENTRYPOINT ["/bin/controller-coordinator"]