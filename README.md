# 🚴‍♂️ GPS Tour Visualizer

An interactive web application for visualizing multiple GPX bike tours on a single map. Upload your GPX files and see all your rides plotted together with overlapping tracks, statistics, and interactive controls.

## Features

- **Interactive Map**: Built with Leaflet.js for smooth panning and zooming
- **Multiple GPX Support**: Load and display multiple GPX files simultaneously
- **Color-coded Tours**: Each tour gets a unique color for easy identification
- **Toggle Visibility**: Show/hide individual tours with checkboxes
- **Tour Statistics**: View distance, elevation gain, duration, and more
- **Drag & Drop**: Simply drag GPX files onto the map to load them
- **Responsive Design**: Works on desktop and mobile devices
- **Popup Details**: Click on any tour to see detailed information
- **Auto-fit View**: Automatically adjusts map view to show all tours

## How to Use

1. **Open the Application**: Open `index.html` in your web browser
2. **Load GPX Files**: Either:
   - Click "Select GPX Files" and choose multiple `.gpx` files
   - Drag and drop GPX files directly onto the map area
3. **View Your Tours**: All tours will be displayed on the map with different colors
4. **Interact with Tours**:
   - Check/uncheck boxes in the sidebar to show/hide tours
   - Click on tour names to zoom to that specific tour
   - Click on map lines to see tour details in a popup
5. **View Statistics**: See total distance, number of tours, and other stats in the sidebar

## File Structure

```
gps-visualizer/
├── index.html          # Main HTML file with UI
├── gpx-parser.js       # GPX file parsing functionality
├── map-visualizer.js   # Map and visualization logic
└── README.md          # This file
```

## Technical Details

### GPX Support
The application supports standard GPX files with:
- **Tracks (`<trk>`)**: Main GPS recordings with track segments
- **Routes (`<rte>`)**: Planned routes (displayed with dashed lines)
- **Waypoints (`<wpt>`)**: Points of interest (displayed as circle markers)

### Calculated Statistics
For each tour, the app calculates:
- **Distance**: Total route distance using Haversine formula
- **Elevation Gain/Loss**: Cumulative elevation changes
- **Duration**: Time difference between first and last GPS points
- **Bounds**: Geographic boundaries of the tour

### Browser Compatibility
- Modern browsers with ES6+ support
- Chrome, Firefox, Safari, Edge (recent versions)
- Mobile browsers supported

## Customization

### Colors
Tours are automatically assigned colors from a predefined palette. You can modify the color array in `map-visualizer.js`:

```javascript
this.colors = [
    '#e74c3c', '#3498db', '#2ecc71', '#f39c12', '#9b59b6',
    // Add more colors here
];
```

### Map Tiles
The default map uses OpenStreetMap tiles. You can change the tile provider in `map-visualizer.js`:

```javascript
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    // Replace with your preferred tile provider
});
```

## Troubleshooting

### GPX Files Not Loading
- Ensure files have `.gpx` extension
- Check that GPX files are valid XML
- Large files may take a moment to process

### Tours Not Displaying
- Verify your GPX files contain track points (`<trkpt>`)
- Check browser console for error messages
- Ensure GPS coordinates are valid (latitude/longitude)

### Performance Issues
- Large GPX files (>1MB) may slow down the interface
- Consider simplifying tracks with fewer points if needed
- Close browser's developer tools if open

## Example GPX Structure

A minimal GPX file structure:

```xml
<?xml version="1.0"?>
<gpx version="1.1" creator="GPS device">
  <metadata>
    <name>My Bike Tour</name>
    <desc>A scenic ride through the countryside</desc>
  </metadata>
  <trk>
    <name>Bike Tour Track</name>
    <trkseg>
      <trkpt lat="52.5200" lon="13.4050">
        <ele>50</ele>
        <time>2023-06-15T10:00:00Z</time>
      </trkpt>
      <trkpt lat="52.5210" lon="13.4060">
        <ele>52</ele>
        <time>2023-06-15T10:01:00Z</time>
      </trkpt>
      <!-- More track points... -->
    </trkseg>
  </trk>
</gpx>
```

## License

This project is open source. Feel free to modify and distribute as needed.

## Contributing

Suggestions and improvements are welcome! Consider adding features like:
- Export functionality for combined tours
- Elevation profile visualization
- Tour comparison tools
- Social sharing capabilities 