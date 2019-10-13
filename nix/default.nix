{ config, lib, pkgs, ... }:

with lib;
let
  cfg = config.services.controller;

  rootConfig = pkgs.writeText "root.conf" ''
    {
      config.dir = "/controller/config"
      server.port = ${builtins.toString cfg.port}
      include required(file("/controller.conf"))
      include file("/controller/controller.conf")
    }
  '';
in {
  imports = [ ./monitoring.nix ];

  options.services.controller = {
    enable = mkOption {
      type = types.bool;
      default = false;
      description = ''
        If enabled, NixOS will start the remote controller
        service
      '';
    };

    port = mkOption {
      description = "Listening port.";
      default = 8090;
      type = types.int;
    };

    jaegerPort = mkOption {
      description = "Jaeger agent port";
      default = 14268;
      type = types.int;
    };

    jaegerHost = mkOption {
      description = "Jaeger agent host";
      default = null;
      type = types.nullOr types.str;
    };

    jaegerSampleRate = mkOption {
      description = "Jaeger Sample Rate";
      default = 1.0;
      type = types.float;
    };

    autoUpdate = mkOption {
      type = types.bool;
      default = false;
      description = ''
        If enabled, NixOS will periodically update the controller
        image version.
      '';
    };

    version = mkOption {
      type = types.str;
      default = "latest";
      description = "Controller version to use";
    };

    dataPath = mkOption {
      type = types.path;
      default = "/var/lib/controller";
      description = "Path to the controller configuration directory";
    };

    controllerConfig = mkOption {
      description = ''
      '';
    };

    updateInterval = mkOption {
      type = types.str;
      default = "02:15";
      example = "hourly";
      description = ''
        Update the controller at this interval. Updates by
        default at 2:15 AM every day.

        The format is described in
        systemd.time(7).
      '';
    };

  };

  config = mkIf cfg.enable {
    networking.firewall = {
      allowedTCPPorts = [ cfg.port 3400 3401 3500 ];
      allowedUDPPorts = [ 1900 1901 1902 ];
    };
	
    docker-containers.controller = {
      image = "janstenpickle/controller:${cfg.version}";
      volumes = [
        "${cfg.dataPath}:/controller:rw"
        "${pkgs.writeText "controller.conf" (builtins.toJSON cfg.controllerConfig)}:/controller.conf:ro"
        "${rootConfig}:/root.conf:ro"
      ];
      cmd = ["/root.conf"];
      extraDockerOptions = [ "--network=host" ];
      environment = mkIf (cfg.jaegerHost != null) {
        JAEGER_ENDPOINT = "http://${cfg.jaegerHost}:${builtins.toString cfg.jaegerPort}/api/traces";
        JAEGER_SAMPLER_PARAM = "${builtins.toString cfg.jaegerSampleRate}";
      };
    };

    systemd.services.docker-controller = {
      requires = [ "controller-config-permissions.service" ];
    };

    systemd.services.controller-config-permissions = {
      description = "Fix ownership of controller config";
      script = ''
        exec chown -R 9000 ${cfg.dataPath}
      '';
      before = [ "docker-controller.service" ];
      serviceConfig = {
        Type = "oneshot";
      };
    };

    systemd.services.update-controller = mkIf cfg.autoUpdate
      { description = "Update Controller Version";
        path  = [ pkgs.su pkgs.docker ];
        script =
          ''
            docker pull janstenpickle/controller:${cfg.version} && docker stop docker-controller.service
          '';
      };

    systemd.timers.update-controller = mkIf cfg.autoUpdate
      { description = "Update controller";
        partOf      = [ "update-controller.service" ];
        wantedBy    = [ "timers.target" ];
        timerConfig.OnCalendar = cfg.updateInterval;
      };
  };
}
