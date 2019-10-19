{ config, lib, pkgs, ... }:

with lib;
let
  cfg = config.services.controller.monitoring;
  mainCfg = config.services.controller;
  promCfg = config.services.prometheus;
in {
  imports = [ ./grafana-dashboard.nix ];

  options.services.controller.monitoring = {

    enableAll = mkEnableOption "Enable all monitoring for Controller";
    enableJaeger = mkEnableOption "Enable Jaeger monitoring for Controller";

    jaegerVersion = mkOption {
      description = "Jaeger All-in-one Version";
      default = "1.14";
      type = types.str;
    };

    jaegerAgentPort = mkOption {
      description = "Jaeger agent port";
      default = 14268;
      type = types.int;
    };

    jaegerWebPort = mkOption {
      description = "Jaeger Web UI port";
      default = 16686;
      type = types.int;
    };

    enablePrometheus = mkEnableOption "Enable Prometheus monitoring for Controller";

    enableGrafana = mkEnableOption "Enable Grafana monitoring for Controller";
  };

  config = {
    docker-containers.jaeger = mkIf (cfg.enableJaeger || cfg.enableAll)  {
      image = "jaegertracing/all-in-one:${cfg.jaegerVersion}";
      ports = [ "${builtins.toString cfg.jaegerAgentPort}:14268" "${builtins.toString cfg.jaegerWebPort}:16686" ];
    };

    services.controller = mkIf (cfg.enableJaeger || cfg.enable) {
      jaegerHost = "localhost";
      jaegerPort = cfg.jaegerAgentPort;
    };

    services.prometheus = mkIf (cfg.enablePrometheus || cfg.enableAll)  {
      enable = true;
      scrapeConfigs = 
        [
          {
            job_name = "local-controller";
            static_configs = [
              {
                targets = [ "localhost:${builtins.toString mainCfg.port}" ];
                labels = { instance = "local"; };
              }
            ];
          }
       ];
     };

    services.grafana = mkIf (cfg.enablePrometheus || cfg.enableAll) {
      enable = true;
      addr = "0.0.0.0";
      analytics.reporting.enable = false;
      provision.enable = true;
      provision.datasources =[
        {
          name = "Controller Prometheus";
          user = "admin";
          type = "prometheus";
          url = "http://${promCfg.listenAddress}";
          jsonData = {
            timeInterval = "30s";
          };
          isDefault = true;
        }
      ];
      provision.dashboards = [
        {
          name = "Controller";
          options.path = "${config.services.grafana.dataDir}/controller";
        }
      ];
    }; 
  };

}
