import { Alert, AlertDescription } from "@/components/ui/alert.tsx";
import { Badge } from "@/components/ui/badge.tsx";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card.tsx";
import {
  LandingPageState,
  OrderAcknowledged,
  OrderFilled,
  OrderPartiallyFilled,
  PublicWebsocketMessage,
} from "@/util/model";
import { parseQuoteMessage, setupPublicWebsocket } from "@/util/rest.ts";
import { useEffect, useState } from "react";

function TradeTypeBadge({ tradeType }: { tradeType: "BUY" | "SELL" }) {
  if (tradeType === "BUY") {
    return (
      <Badge className="bg-green-500  text-white">BUY</Badge>
    );
  } else {
    return (
      <Badge className="bg-red-500  text-white">SELL</Badge>
    );
  }
}

function OrderTypeBadge({ orderType }: { orderType: "Market" | "Limit" }) {
  if (orderType === "Market") {
    return (
      <Badge className="bg-orange-500  text-white">
        Market
      </Badge>
    );
  } else {
    return (
      <Badge className="bg-blue-500  text-white">Limit</Badge>
    );
  }
}

function Transaction({
  message,
}: {
  message: OrderFilled | OrderPartiallyFilled | OrderAcknowledged;
}) {
  const label =
    message.subtype === "orderFilled"
      ? "Filled"
      : message.subtype === "orderPartiallyFilled"
        ? "Partially Filled"
        : "Acknowledged";
  return (
    <div className="flex items-center justify-between w-full">
      <div className="flex items-center gap-2">
        <OrderTypeBadge orderType={message.orderType} />
        <TradeTypeBadge tradeType={message.tradeType} />
        <span>
          {label} {message.size} units to {message.email}
        </span>
      </div>
    </div>
  );
}

function Message({ message }: { message: PublicWebsocketMessage }) {
  // Really should have used a proper union here with a common key across all messages
  if ("subtype" in message) {
    return <Transaction message={message} />;
  }

  if ("type" in message) {
    return (
      <div className="flex items-center justify-between w-full">
        <div className="flex items-center gap-2">
          <Badge>Quote</Badge>
          <span>
            Bid:{parseQuoteMessage(message).bid}, Ask:
            {parseQuoteMessage(message).ask}
          </span>
        </div>
      </div>
    );
  }

  if ("sendingUserEmail" in message) {
    //return <CreditTransferMessage message={message}/>
    return (
      <div className="flex items-center justify-between w-full">
        <div className="flex items-center gap-2">
          <Badge>Credit Send</Badge>
          <span>
            {message.sendingUserEmail} sending {message.targetUserEmail}{" "}
            {message.creditAmount} credits
          </span>
        </div>
      </div>
    );
  }

  if ("orders" in message) {
    return (
      <div className="flex items-center justify-between w-full">
        <div className="flex items-center gap-2">
          <Badge>Cancel</Badge>
          <span>Successfully cancelled {message.orders} orders</span>
        </div>
      </div>
    );
  }

  return (
    <div className="flex items-center justify-between w-full">
      <div className="flex items-center gap-2">
        <Badge>Cancel Failure</Badge>
        <span>No orders successfully cancelled</span>
      </div>
    </div>
  );
}

export function Landing() {
  const [pageState, setPageState] = useState<LandingPageState>({
    quote: { ticker: "N/A", bid: -1, ask: -1 },
    displayedMessages: [],
  });

  useEffect(() => {
    const socket = new WebSocket(
      import.meta.env.DEV
        ? "ws://localhost:7070/ws/public"
        : "wss://api.demo.iainschmitt.com/ws/public"
    );

    setupPublicWebsocket(socket, setPageState);
    return () => {
      if (socket.readyState === WebSocket.OPEN) {
        socket.close();
      }
    };
  }, []);

  return (
    <>
      <div className="min-h-screen flex flex-col w-1/2 mx-auto px-6 py-6">
        <h1 className="text-3xl font-bold mb-6">Fake Stock Market Demo</h1>
        <Card className="w-full">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              Symbol: <Badge>{pageState.quote.ticker}</Badge>
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-4 w-full">
            <div className="text-sm font-bold">Current Quote</div>
            <div className="grid grid-cols-2 gap-6 max-w-md">
              <div className="flex flex-col">
                <span className="text-sm text-muted-foreground">Bid</span>
                <span className="text-lg font-semibold">
                  {pageState.quote.bid}
                </span>
              </div>
              <div className="flex flex-col">
                <span className="text-sm text-muted-foreground">Ask</span>
                <span className="text-lg font-semibold">
                  {pageState.quote.ask}
                </span>
              </div>
            </div>
          </CardContent>
        </Card>

        <div className="space-y-2 mt-6">
          {pageState.displayedMessages.map((message, i) => (
            <Alert key={i}>
              <AlertDescription className="flex items-center justify-between">
                <Message message={message} />
              </AlertDescription>
            </Alert>
          ))}
        </div>
      </div>
    </>
  );
}
