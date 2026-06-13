//+------------------------------------------------------------------+
//|  FXVelociraptor HTTP Bridge EA                                    |
//|  Installez cet EA sur votre MetaTrader 5 (PC ou VPS)             |
//|  Il crée un serveur WebSocket sur le port 8080                    |
//|  que l'application Android utilise pour contrôler MT5            |
//+------------------------------------------------------------------+
#property copyright "FXVelociraptor"
#property version   "1.00"
#property strict

#include <Trade\Trade.mqh>
#include <Trade\PositionInfo.mqh>

CTrade trade;
CPositionInfo positionInfo;

// Configuration
input int    ServerPort    = 8080;        // Port WebSocket
input string AllowedIP     = "0.0.0.0";  // IP autorisée (0.0.0.0 = tous)
input bool   EnableLogging  = true;

//--- Variables globales
int serverSocket = INVALID_HANDLE;
int clientSocket = INVALID_HANDLE;
bool isRunning = false;

//+------------------------------------------------------------------+
int OnInit() {
    Print("🦖 FXVelociraptor Bridge EA démarré sur port ", ServerPort);
    
    // Créer le serveur TCP/WebSocket
    // Note: MT5 utilise les sockets via la bibliothèque MQL5
    serverSocket = SocketCreate(SOCKET_DEFAULT);
    if (serverSocket == INVALID_HANDLE) {
        Print("❌ Impossible de créer le socket: ", GetLastError());
        return INIT_FAILED;
    }
    
    if (!SocketBind(serverSocket, AllowedIP, ServerPort)) {
        Print("❌ Bind échoué sur port ", ServerPort, ": ", GetLastError());
        SocketClose(serverSocket);
        return INIT_FAILED;
    }
    
    if (!SocketListen(serverSocket, 10)) {
        Print("❌ Listen échoué: ", GetLastError());
        SocketClose(serverSocket);
        return INIT_FAILED;
    }
    
    isRunning = true;
    Print("✅ Serveur en écoute sur port ", ServerPort);
    Print("📱 Connectez votre app Android à: ws://VOTRE_IP:", ServerPort);
    
    return INIT_SUCCEEDED;
}

//+------------------------------------------------------------------+
void OnTick() {
    if (!isRunning) return;
    
    // Accepter les nouvelles connexions
    if (clientSocket == INVALID_HANDLE) {
        clientSocket = SocketAccept(serverSocket, 100); // timeout 100ms
        if (clientSocket != INVALID_HANDLE) {
            Print("📱 App Android connectée!");
            // Handshake WebSocket
            DoWebSocketHandshake(clientSocket);
        }
    }
    
    // Lire les messages entrants
    if (clientSocket != INVALID_HANDLE) {
        string message = ReadWebSocketMessage(clientSocket);
        if (message != "") {
            ProcessMessage(clientSocket, message);
        }
        
        // Envoyer les ticks en temps réel
        SendTickUpdate(clientSocket);
    }
}

//+------------------------------------------------------------------+
void ProcessMessage(int socket, string jsonMessage) {
    if (EnableLogging) Print("📨 Reçu: ", jsonMessage);
    
    // Parser l'action
    string action = ExtractJsonString(jsonMessage, "action");
    long reqId = (long)ExtractJsonDouble(jsonMessage, "id");
    
    if (action == "ping") {
        SendMessage(socket, "{\"action\":\"pong\"}");
        return;
    }
    
    if (action == "auth") {
        // Vérifier les credentials MT5
        string login = ExtractJsonString(jsonMessage, "login");
        string server = ExtractJsonString(jsonMessage, "server");
        // En production: vérifier login/password
        string response = StringFormat(
            "{\"action\":\"auth_response\",\"success\":true,\"login\":\"%s\"}",
            IntegerToString(AccountInfoInteger(ACCOUNT_LOGIN))
        );
        SendMessage(socket, response);
        Print("✅ App authentifiée");
        return;
    }
    
    if (action == "get_account") {
        SendAccountInfo(socket, reqId);
        return;
    }
    
    if (action == "get_positions") {
        SendPositions(socket, reqId);
        return;
    }
    
    if (action == "get_candles") {
        string symbol = ExtractJsonString(jsonMessage, "symbol");
        string timeframe = ExtractJsonString(jsonMessage, "timeframe");
        int count = (int)ExtractJsonDouble(jsonMessage, "count");
        SendCandles(socket, reqId, symbol, timeframe, count);
        return;
    }
    
    if (action == "open_order") {
        ExecuteOrder(socket, jsonMessage, reqId);
        return;
    }
    
    if (action == "close_position") {
        long ticket = (long)ExtractJsonDouble(jsonMessage, "ticket");
        bool result = trade.PositionClose(ticket);
        string response = StringFormat(
            "{\"id\":%d,\"action\":\"order_result\",\"ticket\":%d,\"success\":%s}",
            reqId, ticket, result ? "true" : "false"
        );
        SendMessage(socket, response);
        return;
    }
    
    if (action == "modify_position") {
        long ticket = (long)ExtractJsonDouble(jsonMessage, "ticket");
        double sl = ExtractJsonDouble(jsonMessage, "sl");
        double tp = ExtractJsonDouble(jsonMessage, "tp");
        bool result = trade.PositionModify(ticket, sl, tp);
        SendMessage(socket, StringFormat("{\"id\":%d,\"success\":%s}", reqId, result?"true":"false"));
        return;
    }
    
    if (action == "subscribe") {
        // Abonnement au symbole - déjà géré par OnTick
        return;
    }
}

//+------------------------------------------------------------------+
void ExecuteOrder(int socket, string json, long reqId) {
    string symbol = ExtractJsonString(json, "symbol");
    string type = ExtractJsonString(json, "type");
    double lot = ExtractJsonDouble(json, "lot");
    double sl = ExtractJsonDouble(json, "sl");
    double tp = ExtractJsonDouble(json, "tp");
    string comment = ExtractJsonString(json, "comment");
    
    bool result = false;
    ulong ticket = 0;
    
    if (type == "BUY") {
        result = trade.Buy(lot, symbol, 0, sl, tp, comment);
        ticket = trade.ResultOrder();
    } else if (type == "SELL") {
        result = trade.Sell(lot, symbol, 0, sl, tp, comment);
        ticket = trade.ResultOrder();
    }
    
    string response = StringFormat(
        "{\"id\":%d,\"action\":\"order_result\",\"success\":%s,\"ticket\":%d,\"error\":\"%s\"}",
        reqId, result ? "true" : "false", ticket,
        result ? "" : EnumToString((ENUM_TRADE_RETCODE)trade.ResultRetcode())
    );
    SendMessage(socket, response);
    
    if (result) Print("✅ Ordre exécuté: ", type, " ", symbol, " Ticket#", ticket);
    else Print("❌ Échec ordre: ", trade.ResultRetcodeDescription());
}

//+------------------------------------------------------------------+
void SendAccountInfo(int socket, long reqId) {
    string json = StringFormat(
        "{\"id\":%d,\"action\":\"account_update\","
        "\"login\":\"%d\","
        "\"balance\":%.2f,"
        "\"equity\":%.2f,"
        "\"margin\":%.2f,"
        "\"free_margin\":%.2f,"
        "\"profit\":%.2f,"
        "\"currency\":\"%s\","
        "\"leverage\":%d,"
        "\"server\":\"%s\"}",
        reqId,
        AccountInfoInteger(ACCOUNT_LOGIN),
        AccountInfoDouble(ACCOUNT_BALANCE),
        AccountInfoDouble(ACCOUNT_EQUITY),
        AccountInfoDouble(ACCOUNT_MARGIN),
        AccountInfoDouble(ACCOUNT_FREEMARGIN),
        AccountInfoDouble(ACCOUNT_PROFIT),
        AccountInfoString(ACCOUNT_CURRENCY),
        AccountInfoInteger(ACCOUNT_LEVERAGE),
        AccountInfoString(ACCOUNT_SERVER)
    );
    SendMessage(socket, json);
}

//+------------------------------------------------------------------+
void SendPositions(int socket, long reqId) {
    string posJson = "";
    int total = PositionsTotal();
    
    for (int i = 0; i < total; i++) {
        if (positionInfo.SelectByIndex(i)) {
            if (posJson != "") posJson += ",";
            posJson += StringFormat(
                "{\"ticket\":%d,\"symbol\":\"%s\",\"type\":\"%s\","
                "\"lot\":%.2f,\"open_price\":%.5f,\"current_price\":%.5f,"
                "\"sl\":%.5f,\"tp\":%.5f,\"profit\":%.2f,"
                "\"open_time\":%d,\"comment\":\"%s\"}",
                positionInfo.Ticket(),
                positionInfo.Symbol(),
                positionInfo.TypeDescription(),
                positionInfo.Volume(),
                positionInfo.PriceOpen(),
                positionInfo.PriceCurrent(),
                positionInfo.StopLoss(),
                positionInfo.TakeProfit(),
                positionInfo.Profit(),
                positionInfo.Time(),
                positionInfo.Comment()
            );
        }
    }
    
    SendMessage(socket, StringFormat("{\"id\":%d,\"positions\":[%s]}", reqId, posJson));
}

//+------------------------------------------------------------------+
void SendCandles(int socket, long reqId, string symbol, string tf, int count) {
    ENUM_TIMEFRAMES timeframe = StringToTimeframe(tf);
    string candleJson = "";
    
    MqlRates rates[];
    int copied = CopyRates(symbol, timeframe, 0, count, rates);
    
    for (int i = 0; i < copied; i++) {
        if (candleJson != "") candleJson += ",";
        candleJson += StringFormat(
            "{\"time\":%d,\"open\":%.5f,\"high\":%.5f,\"low\":%.5f,\"close\":%.5f,\"volume\":%d}",
            rates[i].time, rates[i].open, rates[i].high, rates[i].low, rates[i].close, rates[i].tick_volume
        );
    }
    
    SendMessage(socket, StringFormat("{\"id\":%d,\"candles\":[%s]}", reqId, candleJson));
}

//+------------------------------------------------------------------+
void SendTickUpdate(int socket) {
    static datetime lastTick = 0;
    MqlTick tick;
    if (SymbolInfoTick(_Symbol, tick) && tick.time != lastTick) {
        lastTick = tick.time;
        string json = StringFormat(
            "{\"action\":\"tick\",\"symbol\":\"%s\",\"bid\":%.5f,\"ask\":%.5f,\"time\":%d}",
            _Symbol, tick.bid, tick.ask, tick.time
        );
        SendMessage(socket, json);
    }
}

//+------------------------------------------------------------------+
// Helpers WebSocket
//+------------------------------------------------------------------+
void DoWebSocketHandshake(int socket) {
    // Lire la requête HTTP d'upgrade WebSocket
    uchar httpBuffer[];
    SocketRead(socket, httpBuffer, 4096, 1000);
    string request = CharArrayToString(httpBuffer);
    
    // Extraire la clé WebSocket
    int keyStart = StringFind(request, "Sec-WebSocket-Key: ") + 19;
    int keyEnd = StringFind(request, "\r\n", keyStart);
    string wsKey = StringSubstr(request, keyStart, keyEnd - keyStart);
    
    // Calculer l'accept key (SHA1 + Base64)
    string magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    string combined = wsKey + magic;
    // Simplified: en production, calculer le vrai SHA1+Base64
    
    string response = "HTTP/1.1 101 Switching Protocols\r\n"
                     "Upgrade: websocket\r\n"
                     "Connection: Upgrade\r\n"
                     "Sec-WebSocket-Accept: " + combined + "\r\n\r\n";
    
    uchar responseBytes[];
    StringToCharArray(response, responseBytes, 0, StringLen(response));
    SocketSend(socket, responseBytes, ArraySize(responseBytes));
}

void SendMessage(int socket, string message) {
    uchar bytes[];
    StringToCharArray(message, bytes, 0, StringLen(message));
    int len = ArraySize(bytes);
    
    // Frame WebSocket simplifié
    uchar frame[];
    ArrayResize(frame, len + 10);
    frame[0] = 0x81; // FIN + text frame
    
    int headerLen = 2;
    if (len < 126) {
        frame[1] = (uchar)len;
    } else {
        frame[1] = 126;
        frame[2] = (uchar)(len >> 8);
        frame[3] = (uchar)(len & 0xFF);
        headerLen = 4;
    }
    
    for (int i = 0; i < len; i++) frame[headerLen + i] = bytes[i];
    SocketSend(socket, frame, headerLen + len);
}

string ReadWebSocketMessage(int socket) {
    uchar buffer[];
    int bytesRead = SocketRead(socket, buffer, 8192, 50);
    if (bytesRead <= 2) return "";
    
    // Décoder le frame WebSocket
    bool masked = (buffer[1] & 0x80) != 0;
    int payloadLen = buffer[1] & 0x7F;
    int dataStart = 2;
    
    if (payloadLen == 126) { payloadLen = (buffer[2] << 8) | buffer[3]; dataStart = 4; }
    if (masked) {
        uchar mask[4];
        for (int i = 0; i < 4; i++) mask[i] = buffer[dataStart + i];
        dataStart += 4;
        for (int i = 0; i < payloadLen; i++) {
            buffer[dataStart + i] ^= mask[i % 4];
        }
    }
    
    uchar payload[];
    ArrayResize(payload, payloadLen + 1);
    for (int i = 0; i < payloadLen; i++) payload[i] = buffer[dataStart + i];
    payload[payloadLen] = 0;
    return CharArrayToString(payload, 0, payloadLen);
}

// Helpers JSON simples
string ExtractJsonString(string json, string key) {
    string searchKey = "\"" + key + "\":\"";
    int start = StringFind(json, searchKey);
    if (start < 0) return "";
    start += StringLen(searchKey);
    int end = StringFind(json, "\"", start);
    return StringSubstr(json, start, end - start);
}

double ExtractJsonDouble(string json, string key) {
    string searchKey = "\"" + key + "\":";
    int start = StringFind(json, searchKey);
    if (start < 0) return 0;
    start += StringLen(searchKey);
    int end = start;
    while (end < StringLen(json) && StringSubstr(json, end, 1) != "," && 
           StringSubstr(json, end, 1) != "}") end++;
    return StringToDouble(StringSubstr(json, start, end - start));
}

ENUM_TIMEFRAMES StringToTimeframe(string tf) {
    if (tf == "M1") return PERIOD_M1;
    if (tf == "M5") return PERIOD_M5;
    if (tf == "M15") return PERIOD_M15;
    if (tf == "M30") return PERIOD_M30;
    if (tf == "H1") return PERIOD_H1;
    if (tf == "H4") return PERIOD_H4;
    if (tf == "D1") return PERIOD_D1;
    return PERIOD_H1;
}

//+------------------------------------------------------------------+
void OnDeinit(const int reason) {
    isRunning = false;
    if (clientSocket != INVALID_HANDLE) SocketClose(clientSocket);
    if (serverSocket != INVALID_HANDLE) SocketClose(serverSocket);
    Print("👋 FXVelociraptor Bridge EA arrêté");
}
