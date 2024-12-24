{ pkgs ? import <nixos-unstable> {} }:

  pkgs.mkShell {
    buildInputs = [
      pkgs.google-cloud-sdk
    ];
  }

