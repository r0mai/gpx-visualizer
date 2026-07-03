// GPX Parser for extracting GPS coordinates and metadata
class GPXParser {
    constructor() {
        this.tours = [];
    }

    // Parse a GPX file and extract track data
    parseGPX(gpxContent, filename) {
        try {
            const parser = new DOMParser();
            const xmlDoc = parser.parseFromString(gpxContent, 'text/xml');
            
            // Check for parsing errors
            const parserError = xmlDoc.querySelector('parsererror');
            if (parserError) {
                throw new Error('Invalid GPX file format');
            }

            const tracks = xmlDoc.querySelectorAll('trk');
            const routes = xmlDoc.querySelectorAll('rte');
            const waypoints = xmlDoc.querySelectorAll('wpt');

            const tour = {
                filename: filename,
                name: this.extractName(xmlDoc, filename),
                description: this.extractDescription(xmlDoc),
                tracks: [],
                routes: [],
                waypoints: [],
                bounds: null,
                distance: 0,
                elevation: {
                    min: Infinity,
                    max: -Infinity,
                    gain: 0,
                    loss: 0
                },
                time: {
                    start: null,
                    end: null,
                    duration: null
                }
            };

            // Parse tracks
            tracks.forEach(track => {
                const trackData = this.parseTrack(track);
                if (trackData.segments.length > 0) {
                    tour.tracks.push(trackData);
                }
            });

            // Parse routes
            routes.forEach(route => {
                const routeData = this.parseRoute(route);
                if (routeData.points.length > 0) {
                    tour.routes.push(routeData);
                }
            });

            // Parse waypoints
            waypoints.forEach(waypoint => {
                const waypointData = this.parseWaypoint(waypoint);
                if (waypointData) {
                    tour.waypoints.push(waypointData);
                }
            });

            // Calculate tour statistics
            this.calculateTourStats(tour);

            return tour;
        } catch (error) {
            console.error('Error parsing GPX file:', filename, error);
            throw error;
        }
    }

    // Extract name from GPX metadata
    extractName(xmlDoc, filename) {
        const nameElement = xmlDoc.querySelector('metadata > name') || 
                           xmlDoc.querySelector('trk > name') ||
                           xmlDoc.querySelector('rte > name');
        
        if (nameElement && nameElement.textContent.trim()) {
            return nameElement.textContent.trim();
        }
        
        // Fallback to filename without extension
        return filename.replace(/\.[^/.]+$/, '');
    }

    // Extract description from GPX metadata
    extractDescription(xmlDoc) {
        const descElement = xmlDoc.querySelector('metadata > desc') || 
                           xmlDoc.querySelector('trk > desc') ||
                           xmlDoc.querySelector('rte > desc');
        
        return descElement ? descElement.textContent.trim() : '';
    }

    // Parse a track element
    parseTrack(trackElement) {
        const name = trackElement.querySelector('name')?.textContent || '';
        const segments = [];
        
        const trackSegments = trackElement.querySelectorAll('trkseg');
        trackSegments.forEach(segment => {
            const points = [];
            const trackPoints = segment.querySelectorAll('trkpt');
            
            trackPoints.forEach(point => {
                const lat = parseFloat(point.getAttribute('lat'));
                const lon = parseFloat(point.getAttribute('lon'));
                const ele = point.querySelector('ele')?.textContent;
                const time = point.querySelector('time')?.textContent;
                
                if (!isNaN(lat) && !isNaN(lon)) {
                    points.push({
                        lat: lat,
                        lon: lon,
                        ele: ele ? parseFloat(ele) : null,
                        time: time ? new Date(time) : null
                    });
                }
            });
            
            if (points.length > 0) {
                segments.push({ points });
            }
        });
        
        return { name, segments };
    }

    // Parse a route element
    parseRoute(routeElement) {
        const name = routeElement.querySelector('name')?.textContent || '';
        const points = [];
        
        const routePoints = routeElement.querySelectorAll('rtept');
        routePoints.forEach(point => {
            const lat = parseFloat(point.getAttribute('lat'));
            const lon = parseFloat(point.getAttribute('lon'));
            const ele = point.querySelector('ele')?.textContent;
            const name = point.querySelector('name')?.textContent;
            
            if (!isNaN(lat) && !isNaN(lon)) {
                points.push({
                    lat: lat,
                    lon: lon,
                    ele: ele ? parseFloat(ele) : null,
                    name: name || ''
                });
            }
        });
        
        return { name, points };
    }

    // Parse a waypoint element
    parseWaypoint(waypointElement) {
        const lat = parseFloat(waypointElement.getAttribute('lat'));
        const lon = parseFloat(waypointElement.getAttribute('lon'));
        const ele = waypointElement.querySelector('ele')?.textContent;
        const name = waypointElement.querySelector('name')?.textContent;
        const desc = waypointElement.querySelector('desc')?.textContent;
        
        if (!isNaN(lat) && !isNaN(lon)) {
            return {
                lat: lat,
                lon: lon,
                ele: ele ? parseFloat(ele) : null,
                name: name || '',
                description: desc || ''
            };
        }
        
        return null;
    }

    // Calculate tour statistics
    calculateTourStats(tour) {
        let allPoints = [];
        let totalDistance = 0;
        let totalElevationGain = 0;
        let totalElevationLoss = 0;
        let minElevation = Infinity;
        let maxElevation = -Infinity;
        let startTime = null;
        let endTime = null;

        // Collect all points from tracks
        tour.tracks.forEach(track => {
            track.segments.forEach(segment => {
                segment.points.forEach((point, index) => {
                    allPoints.push(point);
                    
                    // Calculate distance between consecutive points
                    if (index > 0) {
                        const prevPoint = segment.points[index - 1];
                        const distance = this.calculateDistance(
                            prevPoint.lat, prevPoint.lon,
                            point.lat, point.lon
                        );
                        totalDistance += distance;
                    }
                    
                    // Track elevation changes
                    if (point.ele !== null) {
                        minElevation = Math.min(minElevation, point.ele);
                        maxElevation = Math.max(maxElevation, point.ele);
                        
                        if (index > 0) {
                            const prevPoint = segment.points[index - 1];
                            if (prevPoint.ele !== null) {
                                const elevationChange = point.ele - prevPoint.ele;
                                if (elevationChange > 0) {
                                    totalElevationGain += elevationChange;
                                } else {
                                    totalElevationLoss += Math.abs(elevationChange);
                                }
                            }
                        }
                    }
                    
                    // Track time
                    if (point.time) {
                        if (!startTime || point.time < startTime) {
                            startTime = point.time;
                        }
                        if (!endTime || point.time > endTime) {
                            endTime = point.time;
                        }
                    }
                });
            });
        });

        // Collect points from routes
        tour.routes.forEach(route => {
            route.points.forEach(point => {
                allPoints.push(point);
                
                if (point.ele !== null) {
                    minElevation = Math.min(minElevation, point.ele);
                    maxElevation = Math.max(maxElevation, point.ele);
                }
            });
        });

        // Calculate bounds
        if (allPoints.length > 0) {
            const lats = allPoints.map(p => p.lat);
            const lons = allPoints.map(p => p.lon);
            
            tour.bounds = {
                north: Math.max(...lats),
                south: Math.min(...lats),
                east: Math.max(...lons),
                west: Math.min(...lons)
            };
        }

        // Set calculated values
        tour.distance = totalDistance;
        tour.elevation.min = minElevation === Infinity ? null : minElevation;
        tour.elevation.max = maxElevation === -Infinity ? null : maxElevation;
        tour.elevation.gain = totalElevationGain;
        tour.elevation.loss = totalElevationLoss;
        tour.time.start = startTime;
        tour.time.end = endTime;
        tour.time.duration = startTime && endTime ? endTime - startTime : null;
    }

    // Calculate distance between two points using Haversine formula
    calculateDistance(lat1, lon1, lat2, lon2) {
        const R = 6371; // Earth's radius in kilometers
        const dLat = this.toRadians(lat2 - lat1);
        const dLon = this.toRadians(lon2 - lon1);
        const a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                  Math.cos(this.toRadians(lat1)) * Math.cos(this.toRadians(lat2)) *
                  Math.sin(dLon/2) * Math.sin(dLon/2);
        const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R * c;
    }

    // Convert degrees to radians
    toRadians(degrees) {
        return degrees * (Math.PI / 180);
    }

    // Format distance for display
    formatDistance(distance) {
        if (distance < 1) {
            return `${Math.round(distance * 1000)} m`;
        }
        return `${distance.toFixed(1)} km`;
    }

    // Format duration for display
    formatDuration(duration) {
        if (!duration) return '';
        
        const hours = Math.floor(duration / (1000 * 60 * 60));
        const minutes = Math.floor((duration % (1000 * 60 * 60)) / (1000 * 60));
        
        if (hours > 0) {
            return `${hours}h ${minutes}m`;
        }
        return `${minutes}m`;
    }
}

// Export for use in other files
window.GPXParser = GPXParser; 