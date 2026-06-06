# Frontend

This frontend now uses React with Vite and consumes fraud alerts over a websocket connection for live updates.

## Files

- `index.html`: Vite entry HTML
- `src/main.jsx`: React bootstrap
- `src/App.jsx`: dashboard components, websocket subscriptions, snapshot sync, and report flow
- `styles.css`: responsive AML dashboard styling

## Run

Install dependencies and start the Vite dev server:

```powershell
cd frontend
npm install
npm run dev
```

Then open the local URL printed by Vite, usually `http://localhost:5173`.

The dashboard expects the backend at `http://localhost:8080/api`.
The live alert stream connects to the backend websocket endpoint at `http://localhost:8080/ws`.

## Current integration notes

- Alerts are loaded over STOMP/SockJS via `/app/alerts.snapshot` and `/user/queue/alerts.snapshot`
- Live fraud alerts are pushed over `/topic/fraud-alerts`
- Reports are requested from `POST /api/alerts/{transactionId}/report`
- The HTTP alerts endpoint remains available as a fallback if the websocket connection is unavailable
