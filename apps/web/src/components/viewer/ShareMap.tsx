"use client";

import { useEffect, useRef } from "react";
import type { Feature, Polygon } from "geojson";
import * as maplibregl from "maplibre-gl";
import type { GeoJSONSource, Map as MapLibreMap, Marker } from "maplibre-gl";
import styles from "./share-viewer.module.css";

const MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty";
const FOLLOW_THRESHOLD_METERS = 8;

type Fix = {
  latitude: number;
  longitude: number;
  accuracyMeters: number;
  capturedAt: string;
  speedMps: number | null;
};

function accuracyFeature(fix: Fix): Feature<Polygon> {
  const coordinates: [number, number][] = [];
  const latitudeScale = 111_320;
  const longitudeScale = Math.max(1, latitudeScale * Math.cos((fix.latitude * Math.PI) / 180));
  const radius = Math.max(1, Math.min(fix.accuracyMeters, 5_000));

  for (let index = 0; index <= 48; index += 1) {
    const angle = (index / 48) * Math.PI * 2;
    coordinates.push([
      fix.longitude + (Math.cos(angle) * radius) / longitudeScale,
      fix.latitude + (Math.sin(angle) * radius) / latitudeScale,
    ]);
  }

  return {
    type: "Feature",
    properties: {},
    geometry: { type: "Polygon", coordinates: [coordinates] },
  };
}

function distanceMeters(first: Fix, second: Fix): number {
  const latitudeMeters = (second.latitude - first.latitude) * 111_320;
  const meanLatitude = ((first.latitude + second.latitude) / 2 * Math.PI) / 180;
  const longitudeMeters = (second.longitude - first.longitude) * 111_320 * Math.cos(meanLatitude);
  return Math.hypot(latitudeMeters, longitudeMeters);
}

export default function ShareMap({ fix, stale }: { fix: Fix; stale: boolean }) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const markerRef = useRef<Marker | null>(null);
  const fixRef = useRef(fix);

  useEffect(() => {
    const previousFix = fixRef.current;
    fixRef.current = fix;
    const point: [number, number] = [fix.longitude, fix.latitude];
    const map = mapRef.current;
    if (!map) return;

    const locationChanged = previousFix.latitude !== fix.latitude || previousFix.longitude !== fix.longitude;
    const accuracyChanged = previousFix.accuracyMeters !== fix.accuracyMeters;
    if (map.isStyleLoaded() && (locationChanged || accuracyChanged)) {
      const source = map.getSource("accuracy") as GeoJSONSource | undefined;
      source?.setData(accuracyFeature(fix));
    }
    if (locationChanged) {
      markerRef.current?.setLngLat(point);
      if (distanceMeters(previousFix, fix) >= FOLLOW_THRESHOLD_METERS) {
        map.easeTo({ center: point, duration: 600, essential: false });
      }
    }
  }, [fix]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const firstFix = fixRef.current;
    const point: [number, number] = [firstFix.longitude, firstFix.latitude];
    const markerElement = document.createElement("div");
    markerElement.className = stale ? `${styles.mapMarker} ${styles.mapMarkerStale}` : styles.mapMarker;
    markerElement.setAttribute("aria-hidden", "true");

    const map = new maplibregl.Map({
      container,
      style: MAP_STYLE,
      center: point,
      zoom: 14,
      minZoom: 3,
      maxZoom: 19,
      attributionControl: false,
      cooperativeGestures: true,
    });
    mapRef.current = map;
    markerRef.current = new maplibregl.Marker({ element: markerElement, anchor: "center" }).setLngLat(point).addTo(map);
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
    map.addControl(
      new maplibregl.AttributionControl({ compact: false, customAttribution: "© OpenFreeMap · © OpenMapTiles · © OpenStreetMap contributors" }),
      "bottom-right",
    );

    map.on("load", () => {
      const currentFix = fixRef.current;
      map.addSource("accuracy", { type: "geojson", data: accuracyFeature(currentFix) });
      map.addLayer({
        id: "accuracy-fill",
        type: "fill",
        source: "accuracy",
        paint: { "fill-color": "#3e8dff", "fill-opacity": 0.12 },
      });
      map.addLayer({
        id: "accuracy-outline",
        type: "line",
        source: "accuracy",
        paint: { "line-color": "#4a8de8", "line-opacity": 0.55, "line-width": 1.5 },
      });
    });

    return () => {
      markerRef.current?.remove();
      markerRef.current = null;
      map.remove();
      mapRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (!markerRef.current) return;
    const markerElement = markerRef.current.getElement();
    markerElement.className = stale ? `${styles.mapMarker} ${styles.mapMarkerStale}` : styles.mapMarker;
  }, [stale]);

  return (
    <div className={styles.mapFrame}>
      <div className={styles.mapCanvas} ref={containerRef} role="img" aria-label="Map showing the sender’s latest shared location and its reported accuracy area" />
      <div className={styles.mapOverlay} aria-hidden="true">
        <span className={styles.mapScale} />
      </div>
    </div>
  );
}
