FROM debian

COPY controller-all-in-one /bin/
COPY ui /opt/controller/ui

RUN useradd --system --create-home --uid 9000 --gid 0 controller
USER 9000:0
ENV CONTROLLER_FRONTEND_PATH /opt/controller/ui

ENTRYPOINT ["/bin/controller-all-in-one"]