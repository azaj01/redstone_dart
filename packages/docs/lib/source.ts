import { docs } from '@/.source';
import { loader } from 'fumadocs-core/source';
import { createElement } from 'react';
import {
  Home,
  Rocket,
  Terminal,
  Box,
  Sword,
  Bug,
  Zap,
  Globe,
  User,
  Layout,
  RefreshCw,
  Gift,
  Hammer,
} from 'lucide-react';

const icons: Record<string, React.ElementType> = {
  Home,
  Rocket,
  Terminal,
  Box,
  Sword,
  Bug,
  Zap,
  Globe,
  User,
  Layout,
  RefreshCw,
  Gift,
  Hammer,
};

export const source = loader({
  baseUrl: '/docs',
  source: docs.toFumadocsSource(),
  icon(icon) {
    if (!icon || !(icon in icons)) return undefined;
    return createElement(icons[icon]);
  },
});
