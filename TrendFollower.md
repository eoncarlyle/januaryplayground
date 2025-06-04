# Trend Following Trading Bot Detail

## Price History and Moving Averages
Let $p_t$ represent the price at time $t$. We maintain a window of prices:

$P = \{p_{t-n}, p_{t-n+1}, ..., p_{t-1}, p_t\}$

where $n$ is the lookback period.

The short-term moving average $\text{SMA}_s$ over period $s$ is:

$\text{SMA}_s = \frac{1}{s}\sum_{i=t-s+1}^t p_i$

The long-term moving average $\text{SMA}_l$ over period $l$ is:

$\text{SMA}_l = \frac{1}{l}\sum_{i=t-l+1}^t p_i$

## Trend Calculation
The trend strength $\tau$ is calculated as the relative difference between short and long moving averages:

$\tau = \frac{\text{SMA}_s - \text{SMA}_l}{\text{SMA}_l}$

## Position Sizing
Let $\text{pos}$ be the current position and $\text{pos}_{\text{max}}$ be the maximum allowed position.
The desired trade size $q$ is determined by:

$q = \begin{cases}
\min(q_{\text{std}}, \text{pos}_{\text{max}} - \text{pos}) & \text{if}\ \tau > \theta \text{ and } \text{pos} < \text{pos}_{\text{max}} \\
\min(q_{\text{std}}, \text{pos}_{\text{max}} + \text{pos}) & \text{if}\ \tau < -\theta \text{ and } \text{pos} > -\text{pos}_{\text{max}} \\
0 & \text{otherwise}
\end{cases}$

where:
- $q_{\text{std}}$ is the standard position size
- $\theta$ is the trend threshold

## Risk Management
For a position entered at price $p_{\text{entry}}$, the percentage profit/loss $\pi$ is:

$\pi = \frac{p_t - p_{\text{entry}}}{p_{\text{entry}}}$

Exit signals are generated when:

$|\pi| \geq \pi_{\text{stop}}$ (Stop loss)
or
$\pi \geq \pi_{\text{target}}$ (Take profit)

where:
- $\pi_{\text{stop}}$ is the stop loss percentage
- $\pi_{\text{target}}$ is the take profit percentage

## Trade Timing
Let $t_{\text{last}}$ be the time of the last trade. A new trade is allowed when:

$t - t_{\text{last}} > \Delta t_{\text{min}}$

and

$\text{rand}() > \phi$

where:
- $\Delta t_{\text{min}}$ is the minimum time between trades
- $\phi$ is the trade probability threshold (e.g., 0.7)
- $\text{rand}()$ generates a uniform random number in [0,1]

## Position Price Updates
When a new trade of size $q_{\text{new}}$ is executed at price $p_{\text{new}}$, the average position price $p_{\text{avg}}$ is updated as:

$p_{\text{avg}} = \frac{\text{pos} \cdot p_{\text{avg}} + q_{\text{new}} \cdot p_{\text{new}}}{\text{pos} + q_{\text{new}}}$

where $\text{pos}$ is the position size before the new trade.
