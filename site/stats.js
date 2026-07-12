// Statistics dashboard: aggregates the loaded tours and renders
// summary cards, a riding-calendar heatmap and a set of charts.
class StatsDashboard {
    constructor() {
        this.tours = [];
        this.charts = [];
        this.isOpen = false;

        this.overlay = document.getElementById('stats-dashboard');
        this.content = document.getElementById('dashboard-content');

        document.getElementById('open-stats-dashboard').addEventListener('click', () => this.open());
        document.getElementById('close-stats-dashboard').addEventListener('click', () => this.close());
        document.addEventListener('keydown', (event) => {
            if (event.key === 'Escape' && this.isOpen) {
                this.close();
            }
        });
    }

    // Called by MapVisualizer whenever the set of visible tours changes.
    setTours(tours) {
        this.tours = tours;
        if (this.isOpen) {
            this.render();
        }
    }

    open() {
        this.isOpen = true;
        this.overlay.style.display = 'block';
        this.render();
    }

    close() {
        this.isOpen = false;
        this.overlay.style.display = 'none';
        this.destroyCharts();
    }

    destroyCharts() {
        this.charts.forEach(chart => chart.destroy());
        this.charts = [];
    }

    render() {
        // Dark theme for all charts.
        Chart.defaults.color = '#c7ccd1';
        Chart.defaults.borderColor = 'rgba(255, 255, 255, 0.08)';

        this.destroyCharts();
        this.content.innerHTML = '';

        // Only rides with a timestamp can be placed on time-based charts.
        const rides = this.tours
            .filter(t => t.time.start && t.distance > 0)
            .sort((a, b) => a.time.start - b.time.start);

        if (rides.length === 0) {
            this.content.innerHTML = '<div class="dashboard-empty">No rides with GPS timestamps loaded.</div>';
            return;
        }

        this.renderSummaryCards(rides);
        this.renderHeatmap(rides);

        const grid = document.createElement('div');
        grid.className = 'chart-grid';
        this.content.appendChild(grid);

        this.renderWeeklyDistance(grid, rides);
        this.renderCumulativeDistance(grid, rides);
        this.renderSpeedTrend(grid, rides);
        this.renderDistanceHistogram(grid, rides);
        this.renderMaxDistanceHistogram(grid, rides);
        this.renderMaxDistanceRecord(grid, rides);
        this.renderDistanceVsClimb(grid, rides);
        this.renderDayOfWeek(grid, rides);
        this.renderStartHour(grid, rides);
    }

    // ---------- aggregation helpers ----------

    dateKey(date) {
        const y = date.getFullYear();
        const m = String(date.getMonth() + 1).padStart(2, '0');
        const d = String(date.getDate()).padStart(2, '0');
        return `${y}-${m}-${d}`;
    }

    // Monday of the week containing the given date, at local midnight.
    weekStart(date) {
        const d = new Date(date.getFullYear(), date.getMonth(), date.getDate());
        const day = (d.getDay() + 6) % 7; // 0 = Monday
        d.setDate(d.getDate() - day);
        return d;
    }

    formatHours(ms) {
        const hours = Math.floor(ms / 3600000);
        const minutes = Math.round((ms % 3600000) / 60000);
        return `${hours}h ${minutes}m`;
    }

    formatDateShort(date) {
        return date.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
    }

    longestStreak(rides) {
        const days = [...new Set(rides.map(r => this.dateKey(r.time.start)))].sort();
        let best = 0;
        let current = 0;
        let prev = null;
        days.forEach(key => {
            const date = new Date(key + 'T00:00:00');
            if (prev !== null && date - prev === 86400000) {
                current++;
            } else {
                current = 1;
            }
            best = Math.max(best, current);
            prev = date;
        });
        return best;
    }

    // ---------- summary cards ----------

    renderSummaryCards(rides) {
        const totalDistance = rides.reduce((s, r) => s + r.distance, 0);
        const totalClimb = rides.reduce((s, r) => s + r.elevation.gain, 0);
        const totalMoving = rides.reduce((s, r) => s + (r.time.moving || 0), 0);
        const longest = rides.reduce((a, b) => (b.distance > a.distance ? b : a));
        const biggestClimb = rides.reduce((a, b) => (b.elevation.gain > a.elevation.gain ? b : a));
        // Ignore very short rides when looking for the fastest average.
        const speedCandidates = rides.filter(r => r.avgSpeed && r.distance >= 5);
        const fastest = speedCandidates.length > 0
            ? speedCandidates.reduce((a, b) => (b.avgSpeed > a.avgSpeed ? b : a))
            : null;
        const maxSpeed = rides.reduce((s, r) => Math.max(s, r.maxSpeed || 0), 0);
        const streak = this.longestStreak(rides);
        const distCandidates = rides.filter(r => r.maxDistanceFromStart !== null);
        const furthest = distCandidates.length > 0
            ? distCandidates.reduce((a, b) => (b.maxDistanceFromStart > a.maxDistanceFromStart ? b : a))
            : null;

        const cards = [
            { label: 'Rides', value: `${rides.length}` },
            { label: 'Total Distance', value: `${totalDistance.toFixed(0)} km` },
            { label: 'Total Climbing', value: `${totalClimb.toFixed(0)} m` },
            { label: 'Moving Time', value: this.formatHours(totalMoving) },
            {
                label: 'Longest Ride',
                value: `${longest.distance.toFixed(1)} km`,
                detail: this.formatDateShort(longest.time.start)
            },
            {
                label: 'Biggest Climb Day',
                value: `${biggestClimb.elevation.gain.toFixed(0)} m`,
                detail: this.formatDateShort(biggestClimb.time.start)
            },
            fastest ? {
                label: 'Fastest Avg (≥5 km)',
                value: `${fastest.avgSpeed.toFixed(1)} km/h`,
                detail: this.formatDateShort(fastest.time.start)
            } : null,
            maxSpeed > 0 ? { label: 'Top Speed', value: `${maxSpeed.toFixed(1)} km/h` } : null,
            furthest ? {
                label: 'Furthest from Start',
                value: `${furthest.maxDistanceFromStart.toFixed(1)} km`,
                detail: this.formatDateShort(furthest.time.start)
            } : null,
            { label: 'Longest Streak', value: `${streak} day${streak === 1 ? '' : 's'}` },
            { label: 'Avg Ride', value: `${(totalDistance / rides.length).toFixed(1)} km` }
        ].filter(Boolean);

        const container = document.createElement('div');
        container.className = 'summary-cards';
        cards.forEach(card => {
            const el = document.createElement('div');
            el.className = 'summary-card';
            el.innerHTML = `
                <div class="summary-value">${card.value}</div>
                <div class="summary-label">${card.label}</div>
                ${card.detail ? `<div class="summary-detail">${card.detail}</div>` : ''}
            `;
            container.appendChild(el);
        });
        this.content.appendChild(container);
    }

    // ---------- calendar heatmap ----------

    renderHeatmap(rides) {
        const byDay = new Map();
        rides.forEach(r => {
            const key = this.dateKey(r.time.start);
            const entry = byDay.get(key) || { distance: 0, count: 0 };
            entry.distance += r.distance;
            entry.count++;
            byDay.set(key, entry);
        });
        const maxDistance = Math.max(...[...byDay.values()].map(e => e.distance));

        const first = this.weekStart(rides[0].time.start);
        const last = this.weekStart(rides[rides.length - 1].time.start);

        const card = document.createElement('div');
        card.className = 'chart-card heatmap-card';
        card.innerHTML = '<h4>Riding Calendar</h4>';

        const scroller = document.createElement('div');
        scroller.className = 'heatmap-scroller';

        const monthRow = document.createElement('div');
        monthRow.className = 'heatmap-months';
        const grid = document.createElement('div');
        grid.className = 'heatmap-grid';

        const dayNames = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
        let prevMonth = -1;
        let prevYear = -1;
        for (let week = new Date(first); week <= last; week.setDate(week.getDate() + 7)) {
            const monthLabel = document.createElement('div');
            monthLabel.className = 'heatmap-month-label';
            if (week.getMonth() !== prevMonth) {
                // Mark the year on the first label and whenever it changes.
                const options = week.getFullYear() !== prevYear
                    ? { month: 'short', year: 'numeric' }
                    : { month: 'short' };
                monthLabel.textContent = week.toLocaleDateString(undefined, options);
                prevMonth = week.getMonth();
                prevYear = week.getFullYear();
            }
            monthRow.appendChild(monthLabel);

            const column = document.createElement('div');
            column.className = 'heatmap-week';
            for (let dow = 0; dow < 7; dow++) {
                const day = new Date(week);
                day.setDate(day.getDate() + dow);
                const key = this.dateKey(day);
                const entry = byDay.get(key);
                const cell = document.createElement('div');
                cell.className = 'heatmap-cell';
                if (entry) {
                    const level = Math.min(4, Math.max(1, Math.ceil(4 * entry.distance / maxDistance)));
                    cell.dataset.level = level;
                    cell.title = `${dayNames[dow]}, ${day.toLocaleDateString()} — ` +
                        `${entry.distance.toFixed(1)} km` +
                        (entry.count > 1 ? ` (${entry.count} rides)` : '');
                } else {
                    cell.title = `${dayNames[dow]}, ${day.toLocaleDateString()}`;
                }
                column.appendChild(cell);
            }
            grid.appendChild(column);
        }

        scroller.appendChild(monthRow);
        scroller.appendChild(grid);
        card.appendChild(scroller);

        const legend = document.createElement('div');
        legend.className = 'heatmap-legend';
        legend.innerHTML = 'Less ' +
            [0, 1, 2, 3, 4].map(l => `<span class="heatmap-cell" data-level="${l || ''}"></span>`).join('') +
            ' More';
        card.appendChild(legend);

        this.content.appendChild(card);
    }

    // ---------- charts ----------

    createChartCard(grid, title) {
        const card = document.createElement('div');
        card.className = 'chart-card';
        card.innerHTML = `<h4>${title}</h4>`;
        const wrapper = document.createElement('div');
        wrapper.className = 'chart-wrapper';
        const canvas = document.createElement('canvas');
        wrapper.appendChild(canvas);
        card.appendChild(wrapper);
        grid.appendChild(card);
        return canvas;
    }

    addChart(canvas, config) {
        config.options = config.options || {};
        config.options.maintainAspectRatio = false;
        config.options.animation = false;
        this.charts.push(new Chart(canvas, config));
    }

    // Ticks for a linear x axis holding millisecond timestamps.
    timeAxisOptions() {
        return {
            type: 'linear',
            ticks: {
                maxTicksLimit: 8,
                callback: (value) => this.formatDateShort(new Date(value))
            }
        };
    }

    renderWeeklyDistance(grid, rides) {
        const byWeek = new Map();
        rides.forEach(r => {
            const key = this.weekStart(r.time.start).getTime();
            byWeek.set(key, (byWeek.get(key) || 0) + r.distance);
        });

        // Fill in empty weeks so slumps are visible.
        const weekKeys = [...byWeek.keys()].sort((a, b) => a - b);
        const labels = [];
        const data = [];
        for (let t = weekKeys[0]; t <= weekKeys[weekKeys.length - 1]; ) {
            const week = new Date(t);
            labels.push(this.formatDateShort(week));
            data.push(byWeek.get(t) || 0);
            week.setDate(week.getDate() + 7);
            t = week.getTime();
        }

        this.addChart(this.createChartCard(grid, 'Distance per Week'), {
            type: 'bar',
            data: {
                labels: labels,
                datasets: [{
                    label: 'km',
                    data: data,
                    backgroundColor: '#3498db'
                }]
            },
            options: {
                plugins: { legend: { display: false } },
                scales: {
                    x: { ticks: { maxTicksLimit: 12 } },
                    y: { title: { display: true, text: 'km' }, beginAtZero: true }
                }
            }
        });
    }

    renderCumulativeDistance(grid, rides) {
        let total = 0;
        const data = rides.map(r => {
            total += r.distance;
            return { x: r.time.start.getTime(), y: total };
        });

        this.addChart(this.createChartCard(grid, 'Cumulative Distance'), {
            type: 'line',
            data: {
                datasets: [{
                    label: 'km',
                    data: data,
                    borderColor: '#e74c3c',
                    backgroundColor: 'rgba(231, 76, 60, 0.15)',
                    fill: true,
                    pointRadius: 0,
                    stepped: false,
                    tension: 0
                }]
            },
            options: {
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        callbacks: {
                            title: (items) => this.formatDateShort(new Date(items[0].parsed.x)),
                            label: (item) => `${item.parsed.y.toFixed(1)} km total`
                        }
                    }
                },
                scales: {
                    x: this.timeAxisOptions(),
                    y: { title: { display: true, text: 'km' }, beginAtZero: true }
                }
            }
        });
    }

    renderSpeedTrend(grid, rides) {
        const withSpeed = rides.filter(r => r.avgSpeed);
        if (withSpeed.length === 0) return;

        this.addChart(this.createChartCard(grid, 'Average Moving Speed per Ride'), {
            type: 'scatter',
            data: {
                datasets: [{
                    label: 'km/h',
                    data: withSpeed.map(r => ({
                        x: r.time.start.getTime(),
                        y: r.avgSpeed,
                        ride: r
                    })),
                    backgroundColor: '#27ae60'
                }]
            },
            options: {
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        callbacks: {
                            label: (item) => {
                                const ride = item.raw.ride;
                                return `${this.formatDateShort(ride.time.start)}: ` +
                                    `${ride.avgSpeed.toFixed(1)} km/h over ${ride.distance.toFixed(1)} km`;
                            }
                        }
                    }
                },
                scales: {
                    x: this.timeAxisOptions(),
                    y: { title: { display: true, text: 'km/h' } }
                }
            }
        });
    }

    // Generic bucketed distribution chart over one value per ride.
    renderHistogram(grid, title, values, color) {
        if (values.length === 0) return;
        const max = Math.max(...values);
        // Pick a bin width (0.5/1/2/5/10 km...) that gives roughly 10 bins.
        const niceWidths = [0.5, 1, 2, 5, 10, 20, 50, 100];
        const binWidth = niceWidths.find(w => max / w <= 12) || 100;
        const binCount = Math.floor(max / binWidth) + 1;
        const bins = new Array(binCount).fill(0);
        values.forEach(v => {
            bins[Math.floor(v / binWidth)]++;
        });

        this.addChart(this.createChartCard(grid, title), {
            type: 'bar',
            data: {
                labels: bins.map((_, i) => `${i * binWidth}–${(i + 1) * binWidth}`),
                datasets: [{
                    label: 'rides',
                    data: bins,
                    backgroundColor: color
                }]
            },
            options: {
                plugins: { legend: { display: false } },
                scales: {
                    x: { title: { display: true, text: 'km' } },
                    y: {
                        title: { display: true, text: 'rides' },
                        beginAtZero: true,
                        ticks: { precision: 0 }
                    }
                }
            }
        });
    }

    renderDistanceHistogram(grid, rides) {
        this.renderHistogram(grid, 'Ride Distance Distribution',
            rides.map(r => r.distance), '#9b59b6');
    }

    renderMaxDistanceHistogram(grid, rides) {
        this.renderHistogram(grid, 'Max Distance from Start (Distribution)',
            rides.filter(r => r.maxDistanceFromStart !== null).map(r => r.maxDistanceFromStart),
            '#2980b9');
    }

    // Running record of the furthest straight-line distance from the ride's
    // starting point — monotonically increasing over time.
    renderMaxDistanceRecord(grid, rides) {
        const withDist = rides.filter(r => r.maxDistanceFromStart !== null);
        if (withDist.length === 0) return;

        let record = 0;
        const data = withDist.map(r => {
            const newRecord = r.maxDistanceFromStart > record;
            record = Math.max(record, r.maxDistanceFromStart);
            return { x: r.time.start.getTime(), y: record, ride: r, newRecord: newRecord };
        });

        this.addChart(this.createChartCard(grid, 'Max Distance from Start (Record over Time)'), {
            type: 'line',
            data: {
                datasets: [{
                    label: 'km',
                    data: data,
                    borderColor: '#2980b9',
                    backgroundColor: 'rgba(41, 128, 185, 0.15)',
                    fill: true,
                    stepped: true,
                    // Only mark the rides that set a new record.
                    pointRadius: (ctx) => ctx.raw.newRecord ? 4 : 0,
                    pointHoverRadius: (ctx) => ctx.raw.newRecord ? 6 : 0,
                    pointBackgroundColor: '#5dade2'
                }]
            },
            options: {
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        callbacks: {
                            title: (items) => this.formatDateShort(new Date(items[0].parsed.x)),
                            label: (item) => {
                                const ride = item.raw.ride;
                                return item.raw.newRecord
                                    ? `New record: ${ride.maxDistanceFromStart.toFixed(1)} km (${ride.name})`
                                    : `Record: ${item.parsed.y.toFixed(1)} km`;
                            }
                        }
                    }
                },
                scales: {
                    x: this.timeAxisOptions(),
                    y: { title: { display: true, text: 'km from start' }, beginAtZero: true }
                }
            }
        });
    }

    renderDistanceVsClimb(grid, rides) {
        this.addChart(this.createChartCard(grid, 'Distance vs. Climbing'), {
            type: 'scatter',
            data: {
                datasets: [{
                    data: rides.map(r => ({
                        x: r.distance,
                        y: r.elevation.gain,
                        ride: r
                    })),
                    backgroundColor: 'rgba(230, 126, 34, 0.7)'
                }]
            },
            options: {
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        callbacks: {
                            label: (item) => {
                                const ride = item.raw.ride;
                                return `${ride.name} (${this.formatDateShort(ride.time.start)}): ` +
                                    `${ride.distance.toFixed(1)} km, ↗${ride.elevation.gain.toFixed(0)} m`;
                            }
                        }
                    }
                },
                scales: {
                    x: { title: { display: true, text: 'km' }, beginAtZero: true },
                    y: { title: { display: true, text: 'elevation gain (m)' }, beginAtZero: true }
                }
            }
        });
    }

    renderDayOfWeek(grid, rides) {
        const names = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
        const distance = new Array(7).fill(0);
        rides.forEach(r => {
            distance[(r.time.start.getDay() + 6) % 7] += r.distance;
        });

        this.addChart(this.createChartCard(grid, 'Distance by Day of Week'), {
            type: 'bar',
            data: {
                labels: names,
                datasets: [{
                    label: 'km',
                    data: distance,
                    backgroundColor: '#16a085'
                }]
            },
            options: {
                plugins: { legend: { display: false } },
                scales: {
                    y: { title: { display: true, text: 'km' }, beginAtZero: true }
                }
            }
        });
    }

    renderStartHour(grid, rides) {
        const counts = new Array(24).fill(0);
        rides.forEach(r => {
            counts[r.time.start.getHours()]++;
        });

        this.addChart(this.createChartCard(grid, 'Ride Start Times'), {
            type: 'bar',
            data: {
                labels: counts.map((_, h) => `${h}:00`),
                datasets: [{
                    label: 'rides',
                    data: counts,
                    backgroundColor: '#f39c12'
                }]
            },
            options: {
                plugins: { legend: { display: false } },
                scales: {
                    y: {
                        title: { display: true, text: 'rides' },
                        beginAtZero: true,
                        ticks: { precision: 0 }
                    }
                }
            }
        });
    }
}

document.addEventListener('DOMContentLoaded', () => {
    window.statsDashboard = new StatsDashboard();
});
