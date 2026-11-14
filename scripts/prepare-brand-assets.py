#!/usr/bin/env python3
# Copyright 2026 OpenDuo contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
"""Convert the pinned OpenDuo vector masters to Android resources; no raster editing."""
from pathlib import Path
import xml.etree.ElementTree as E

root = Path(__file__).resolve().parent.parent
res = root / 'android/src/main/res'
a = 'http://schemas.android.com/apk/res/android'
aapt = 'http://schemas.android.com/aapt'
E.register_namespace('android', a)
E.register_namespace('aapt', aapt)


def node(parent, name, **attrs):
    return E.SubElement(parent, name, {f'{{{a}}}{k}': str(v) for k, v in attrs.items()})


def save(element, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    E.indent(element, space='    ')
    path.write_text('<?xml version="1.0" encoding="utf-8"?>\n'
                    '<!-- OpenDuo brand asset; excluded from the code licence. '
                    'See third_party/POCKET-IOS-NOTICE.txt. -->\n'
                    + E.tostring(element, encoding='unicode') + '\n')


for appearance, folder in [('light', 'drawable'), ('dark', 'drawable-night')]:
    svg = E.parse(root / 'brand' / f'{appearance}.svg').getroot()
    shapes = [element for element in svg if element.tag.endswith('path')]
    for variant in ('legacy', 'foreground', 'mark', 'monochrome'):
        if variant == 'monochrome' and appearance == 'dark':
            continue
        vector = E.Element('vector', {f'{{{a}}}width': '108dp', f'{{{a}}}height': '108dp',
                                     f'{{{a}}}viewportWidth': '1024', f'{{{a}}}viewportHeight': '1024'})
        if variant == 'legacy':
            background = node(vector, 'path', pathData='M0,0 H1024 V1024 H0 Z')
            attr = E.SubElement(background, f'{{{aapt}}}attr', {'name': 'android:fillColor'})
            gradient = node(attr, 'gradient', startX=0, startY=0, endX=0, endY=1024, type='linear')
            colors = ['#fdfbf4', '#ece6d6'] if appearance == 'light' else ['#161615', '#050505']
            for offset, color in zip((0, 1), colors):
                node(gradient, 'item', offset=offset, color=color)
        parent = vector
        if variant in ('foreground', 'monochrome'):
            parent = node(vector, 'group', scaleX='.88', scaleY='.88', pivotX=512, pivotY=512)
        for shape in shapes:
            attrs = {'pathData': shape.attrib['d'], 'fillColor': shape.attrib.get('fill', '#00000000')}
            if attrs['fillColor'] == 'none':
                attrs['fillColor'] = '#00000000'
            for key, target in [('stroke', 'strokeColor'), ('stroke-width', 'strokeWidth'),
                                ('stroke-linecap', 'strokeLineCap'), ('stroke-linejoin', 'strokeLineJoin')]:
                if key in shape.attrib:
                    attrs[target] = shape.attrib[key]
            if variant == 'monochrome':
                attrs['fillColor'] = '#00000000'
                if 'strokeColor' in attrs:
                    attrs['strokeColor'] = '#FFFFFFFF'
            node(parent, 'path', **attrs)
        name = {'legacy': 'ic_launcher_legacy', 'foreground': 'ic_launcher_foreground',
                'mark': f'ic_brand_mark_{appearance}', 'monochrome': 'ic_launcher_monochrome'}[variant]
        save(vector, res / ('drawable' if variant == 'mark' else folder) / f'{name}.xml')

for suffix, monochrome in [('v26', False), ('v33', True)]:
    icon = E.Element('adaptive-icon')
    node(icon, 'background', drawable='@color/ic_launcher_background')
    node(icon, 'foreground', drawable='@drawable/ic_launcher_foreground')
    if monochrome:
        node(icon, 'monochrome', drawable='@drawable/ic_launcher_monochrome')
    save(icon, res / f'mipmap-anydpi-{suffix}' / 'ic_launcher.xml')

for folder, color in [('values', '#FDFBF4'), ('values-night', '#161615')]:
    resources = E.Element('resources')
    E.SubElement(resources, 'color', {'name': 'ic_launcher_background'}).text = color
    save(resources, res / folder / 'brand.xml')
