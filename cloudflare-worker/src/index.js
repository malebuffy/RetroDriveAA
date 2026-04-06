export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/") {
      return new Response("retrodrive relay online", { status: 200 });
    }

    if (url.pathname === "/ws") {
      const upgradeHeader = request.headers.get("Upgrade");
      if (!upgradeHeader || upgradeHeader.toLowerCase() !== "websocket") {
        return new Response("Expected WebSocket", { status: 426 });
      }  

      const session = url.searchParams.get("session");
      const role = url.searchParams.get("role");
      if (!session || !role) {
        return new Response("Missing session or role", { status: 400 });
      }

      if (role !== "phone" && role !== "car") {
        return new Response("Invalid role", { status: 400 });
      }

      const id = env.RELAY.idFromName(session);
      const stub = env.RELAY.get(id);
      return stub.fetch(request);
    }

    return new Response("Not found", { status: 404 });
  },
};

export class RelayRoom {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.phoneSocket = null;
    this.carSocket = null;
  }

  async fetch(request) {
    const url = new URL(request.url);
    const role = url.searchParams.get("role");

    if (role !== "phone" && role !== "car") {
      return new Response("Invalid role", { status: 400 });
    }

    const webSocketPair = new WebSocketPair();
    const clientSocket = webSocketPair[0];
    const serverSocket = webSocketPair[1];
    serverSocket.accept();

    if (role === "phone") {
      if (this.phoneSocket && this.phoneSocket.readyState === 1) {
        try {
          this.phoneSocket.close(1000, "Replaced by newer phone connection");
        } catch (_) {
        }
      }
      this.phoneSocket = serverSocket;
    } else {
      if (this.carSocket && this.carSocket.readyState === 1) {
        try {
          this.carSocket.close(1000, "Replaced by newer car connection");
        } catch (_) {
        }
      }
      this.carSocket = serverSocket;
    }

    serverSocket.addEventListener("message", (event) => {
      const target = role === "phone" ? this.carSocket : this.phoneSocket;
      if (!target || target.readyState !== 1) {
        return;
      }

      try {
        target.send(event.data);
      } catch (_) {
      }
    });

    serverSocket.addEventListener("close", () => {
      if (role === "phone" && this.phoneSocket === serverSocket) {
        this.phoneSocket = null;
      }
      if (role === "car" && this.carSocket === serverSocket) {
        this.carSocket = null;
      }
    });

    serverSocket.addEventListener("error", () => {
      if (role === "phone" && this.phoneSocket === serverSocket) {
        this.phoneSocket = null;
      }
      if (role === "car" && this.carSocket === serverSocket) {
        this.carSocket = null;
      }
    });

    return new Response(null, { status: 101, webSocket: clientSocket });
  }
}
