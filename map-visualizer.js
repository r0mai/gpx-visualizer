// Map Visualizer for displaying GPX tours on an interactive map
class MapVisualizer {
    constructor() {
        this.map = null;
        this.tours = [];
        this.tourLayers = new Map();
        this.parser = new GPXParser();
        this.colors = [
            '#e74c3c', '#c0392b', '#a93226', '#922b21', '#7b241c',
            '#ff5733', '#ff4757', '#ff3838', '#ff2f2f', '#e55039',
            '#ff6b6b', '#ee5a52', '#ff4757', '#ff3742', '#ff2e63',
            '#d63031', '#b33939', '#a55eea', '#fd79a8', '#e84393'
        ];
        this.colorIndex = 0;
        
        this.initializeMap();
        this.setupEventListeners();
    }

    // Initialize the Leaflet map
    initializeMap() {
        this.map = L.map('map').setView([51.505, -0.09], 2);
        
        // Add tile layer
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
            maxZoom: 18
        }).addTo(this.map);

        // Add scale control
        L.control.scale({
            position: 'bottomright',
            metric: true,
            imperial: false
        }).addTo(this.map);
    }

    // Setup event listeners
    setupEventListeners() {
        const fileInput = document.getElementById('gpx-files');
        fileInput.addEventListener('change', (event) => {
            this.handleFileUpload(event);
        });

        // Drag and drop functionality
        const mapContainer = document.getElementById('map');
        mapContainer.addEventListener('dragover', (event) => {
            event.preventDefault();
            event.dataTransfer.dropEffect = 'copy';
        });

        mapContainer.addEventListener('drop', (event) => {
            event.preventDefault();
            const files = Array.from(event.dataTransfer.files).filter(file => 
                file.name.toLowerCase().endsWith('.gpx')
            );
            if (files.length > 0) {
                this.processFiles(files);
            }
        });
    }

    // Handle file upload
    async handleFileUpload(event) {
        const files = Array.from(event.target.files);
        if (files.length === 0) return;
        
        await this.processFiles(files);
    }

    // Process uploaded GPX files
    async processFiles(files) {
        const loadingElement = document.getElementById('loading');
        const errorContainer = document.getElementById('error-container');
        
        loadingElement.style.display = 'block';
        errorContainer.innerHTML = '';

        const gpxFiles = files.filter(file => 
            file.name.toLowerCase().endsWith('.gpx')
        );

        if (gpxFiles.length === 0) {
            this.showError('No GPX files selected. Please select files with .gpx extension.');
            loadingElement.style.display = 'none';
            return;
        }

        try {
            const promises = gpxFiles.map(file => this.processGPXFile(file));
            const results = await Promise.all(promises);
            
            const successfulTours = results.filter(result => result.success);
            const failedFiles = results.filter(result => !result.success);

            if (successfulTours.length > 0) {
                successfulTours.forEach(result => {
                    this.addTour(result.tour);
                });
                this.updateUI();
                this.fitMapToTours();
            }

            if (failedFiles.length > 0) {
                const errorMessages = failedFiles.map(result => 
                    `${result.filename}: ${result.error}`
                ).join('<br>');
                this.showError(`Failed to parse ${failedFiles.length} file(s):<br>${errorMessages}`);
            }

        } catch (error) {
            this.showError('Error processing files: ' + error.message);
        } finally {
            loadingElement.style.display = 'none';
        }
    }

    // Process a single GPX file
    async processGPXFile(file) {
        return new Promise((resolve) => {
            const reader = new FileReader();
            
            reader.onload = (event) => {
                try {
                    const tour = this.parser.parseGPX(event.target.result, file.name);
                    resolve({ success: true, tour: tour });
                } catch (error) {
                    resolve({ 
                        success: false, 
                        filename: file.name, 
                        error: error.message 
                    });
                }
            };
            
            reader.onerror = () => {
                resolve({ 
                    success: false, 
                    filename: file.name, 
                    error: 'Failed to read file' 
                });
            };
            
            reader.readAsText(file);
        });
    }

    // Add a tour to the visualization
    addTour(tour) {
        // Assign a color to the tour
        tour.color = this.colors[this.colorIndex % this.colors.length];
        this.colorIndex++;

        this.tours.push(tour);
        this.createTourLayer(tour);
        this.addTourToSidebar(tour);
    }

    // Create Leaflet layers for a tour
    createTourLayer(tour) {
        const layerGroup = L.layerGroup();
        const color = tour.color;

        // Add tracks
        tour.tracks.forEach(track => {
            track.segments.forEach(segment => {
                if (segment.points.length > 1) {
                    const latLngs = segment.points.map(point => [point.lat, point.lon]);
                    const polyline = L.polyline(latLngs, {
                        color: color,
                        weight: 3,
                        opacity: 0.8
                    });
                    
                    // Add popup with tour info
                    polyline.bindPopup(this.createTourPopup(tour));
                    layerGroup.addLayer(polyline);
                }
            });
        });

        // Add routes
        tour.routes.forEach(route => {
            if (route.points.length > 1) {
                const latLngs = route.points.map(point => [point.lat, point.lon]);
                const polyline = L.polyline(latLngs, {
                    color: color,
                    weight: 3,
                    opacity: 0.8,
                    dashArray: '5, 5'
                });
                
                polyline.bindPopup(this.createTourPopup(tour));
                layerGroup.addLayer(polyline);
            }
        });

        // Add waypoints
        tour.waypoints.forEach(waypoint => {
            const marker = L.circleMarker([waypoint.lat, waypoint.lon], {
                radius: 5,
                fillColor: color,
                color: '#fff',
                weight: 2,
                opacity: 1,
                fillOpacity: 0.8
            });
            
            let popupContent = `<strong>${waypoint.name || 'Waypoint'}</strong>`;
            if (waypoint.description) {
                popupContent += `<br>${waypoint.description}`;
            }
            if (waypoint.ele !== null) {
                popupContent += `<br>Elevation: ${waypoint.ele.toFixed(0)}m`;
            }
            
            marker.bindPopup(popupContent);
            layerGroup.addLayer(marker);
        });

        this.tourLayers.set(tour.filename, layerGroup);
        layerGroup.addTo(this.map);
    }

    // Create popup content for a tour
    createTourPopup(tour) {
        let content = `<div class="tour-popup">`;
        content += `<h3>${tour.name}</h3>`;
        
        if (tour.description) {
            content += `<p>${tour.description}</p>`;
        }
        
        content += `<div class="tour-popup-stats">`;
        content += `<div><strong>Distance:</strong> ${this.parser.formatDistance(tour.distance)}</div>`;
        
        if (tour.elevation.min !== null && tour.elevation.max !== null) {
            content += `<div><strong>Elevation:</strong> ${tour.elevation.min.toFixed(0)}m - ${tour.elevation.max.toFixed(0)}m</div>`;
        }
        
        if (tour.elevation.gain > 0) {
            content += `<div><strong>Elevation Gain:</strong> ${tour.elevation.gain.toFixed(0)}m</div>`;
        }
        
        if (tour.time.duration) {
            content += `<div><strong>Duration:</strong> ${this.parser.formatDuration(tour.time.duration)}</div>`;
        }
        
        content += `</div></div>`;
        return content;
    }

    // Add tour to sidebar
    addTourToSidebar(tour) {
        const tourList = document.getElementById('tour-list');
        const tourItem = document.createElement('div');
        tourItem.className = 'tour-item';
        tourItem.style.borderLeftColor = tour.color;
        
        tourItem.innerHTML = `
            <input type="checkbox" class="tour-checkbox" checked data-filename="${tour.filename}">
            <div class="tour-info">
                <div class="tour-name">${tour.name}</div>
                <div class="tour-stats">
                    ${this.parser.formatDistance(tour.distance)}
                    ${tour.time.duration ? '• ' + this.parser.formatDuration(tour.time.duration) : ''}
                    ${tour.elevation.gain > 0 ? '• ↗' + tour.elevation.gain.toFixed(0) + 'm' : ''}
                </div>
            </div>
        `;
        
        // Add event listener for checkbox
        const checkbox = tourItem.querySelector('.tour-checkbox');
        checkbox.addEventListener('change', (event) => {
            this.toggleTour(tour.filename, event.target.checked);
        });
        
        // Add click listener to zoom to tour
        tourItem.addEventListener('click', (event) => {
            if (event.target.type !== 'checkbox') {
                this.zoomToTour(tour.filename);
            }
        });
        
        tourList.appendChild(tourItem);
    }

    // Toggle tour visibility
    toggleTour(filename, visible) {
        const layer = this.tourLayers.get(filename);
        if (layer) {
            if (visible) {
                layer.addTo(this.map);
            } else {
                this.map.removeLayer(layer);
            }
        }
        this.updateStatistics();
    }

    // Zoom to a specific tour
    zoomToTour(filename) {
        const tour = this.tours.find(t => t.filename === filename);
        if (tour && tour.bounds) {
            const bounds = L.latLngBounds(
                [tour.bounds.south, tour.bounds.west],
                [tour.bounds.north, tour.bounds.east]
            );
            this.map.fitBounds(bounds, { padding: [20, 20] });
        }
    }

    // Fit map to show all tours
    fitMapToTours() {
        if (this.tours.length === 0) return;
        
        const allBounds = this.tours
            .filter(tour => tour.bounds)
            .map(tour => tour.bounds);
        
        if (allBounds.length === 0) return;
        
        const overallBounds = {
            north: Math.max(...allBounds.map(b => b.north)),
            south: Math.min(...allBounds.map(b => b.south)),
            east: Math.max(...allBounds.map(b => b.east)),
            west: Math.min(...allBounds.map(b => b.west))
        };
        
        const bounds = L.latLngBounds(
            [overallBounds.south, overallBounds.west],
            [overallBounds.north, overallBounds.east]
        );
        
        this.map.fitBounds(bounds, { padding: [20, 20] });
    }

    // Update UI elements
    updateUI() {
        this.updateStatistics();
        document.getElementById('stats-container').style.display = 'block';
    }

    // Update statistics display
    updateStatistics() {
        const visibleTours = this.tours.filter(tour => {
            const checkbox = document.querySelector(`input[data-filename="${tour.filename}"]`);
            return checkbox && checkbox.checked;
        });
        
        const totalDistance = visibleTours.reduce((sum, tour) => sum + tour.distance, 0);
        
        document.getElementById('total-tours').textContent = this.tours.length;
        document.getElementById('total-distance').textContent = this.parser.formatDistance(totalDistance);
        document.getElementById('active-tours').textContent = visibleTours.length;
    }

    // Show error message
    showError(message) {
        const errorContainer = document.getElementById('error-container');
        const errorDiv = document.createElement('div');
        errorDiv.className = 'error';
        errorDiv.innerHTML = message;
        errorContainer.appendChild(errorDiv);
        
        // Auto-hide after 10 seconds
        setTimeout(() => {
            errorDiv.remove();
        }, 10000);
    }
}

// Initialize the map visualizer when the page loads
document.addEventListener('DOMContentLoaded', () => {
    window.mapVisualizer = new MapVisualizer();
}); 