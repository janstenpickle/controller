const port = () => {
    if (process.env.NODE_ENV !== "production") {
        return "8090"
    } else {
        return window.location.port
    }
}

export const baseURL = `${window.location.protocol}//nixos:${port()}`;

export const baseWebsocketURL = `ws://${window.location.hostname}:${port()}`;
