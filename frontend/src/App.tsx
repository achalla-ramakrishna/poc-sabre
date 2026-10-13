import type { ReactElement } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { session } from './auth/session';
import { Layout } from './components/Layout';
import { LoginPage } from './pages/LoginPage';
import { PreviewPage } from './pages/PreviewPage';
import { RuleEditorPage } from './pages/RuleEditorPage';
import { RuleListPage } from './pages/RuleListPage';

function Protected({ children }: { children: ReactElement }) {
  return session.get() ? children : <Navigate to="/login" replace />;
}

export function App() {
  return (
    <Layout>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/rules" element={<Protected><RuleListPage /></Protected>} />
        <Route path="/rules/new" element={<Protected><RuleEditorPage /></Protected>} />
        <Route path="/rules/:ruleId" element={<Protected><RuleEditorPage /></Protected>} />
        <Route path="/preview" element={<Protected><PreviewPage /></Protected>} />
        <Route path="*" element={<Navigate to="/rules" replace />} />
      </Routes>
    </Layout>
  );
}
